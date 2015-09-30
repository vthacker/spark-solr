package com.lucidworks.spark.query;

import com.lucidworks.spark.SolrSupport;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FieldStatsInfo;
import org.apache.solr.client.solrj.response.QueryResponse;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractFieldShardSplitStrategy<T> implements ShardSplitStrategy, Serializable {

  public static Logger log = Logger.getLogger(AbstractFieldShardSplitStrategy.class);

  public List<ShardSplit> getSplits(String shardUrl,
                                    SolrQuery query,
                                    String splitFieldName,
                                    int splitsPerShard)
      throws IOException, SolrServerException
  {
    long _startMs = System.currentTimeMillis();

    List<ShardSplit> splits = new ArrayList<ShardSplit>(splitsPerShard);

    try (SolrClient solrClient = SolrSupport.getHttpSolrClient(shardUrl)) {
      // start by creating splits based on max-min/num splits and then refine to balance out split sizes
      FieldStatsInfo fsi = appendSplits(solrClient, splits, query, shardUrl, splitFieldName, splitsPerShard);

      long docsPerSplit = Math.round(fsi.getCount() / splitsPerShard);

      // magic number to allow split sizes to be a little larger than the desired # of docs per split
      long threshold = Math.round(1.18 * docsPerSplit);

      // balance the splits best we can in three passes over the list
      for (int b = 0; b < 3; b++) {
        splits = balanceSplits(splits, threshold, docsPerSplit, solrClient, fsi);
      }

      // lastly, scan for any small splits that aren't adjacent that can be joined into a larger split
      joinNonAdjacentSmallSplits(fsi, splits, threshold);

      // add a final split to catch missing values if any?
      Long missingCount = fsi.getMissing();
      if (missingCount == null) {
        SolrQuery missingQuery = query.getCopy();
        missingQuery.addFilterQuery("-" + splitFieldName + ":[* TO *]");
        missingQuery.setRows(0);
        QueryResponse qr = solrClient.query(missingQuery);
        missingCount = qr.getResults().getNumFound();
      }

      if (missingCount > 0) {
        ShardSplit missingValuesSplit =
            new FqSplit(query, shardUrl, splitFieldName, "-"+splitFieldName+":[* TO *]");
        missingValuesSplit.setNumHits(missingCount);
        splits.add(missingValuesSplit);
        if (missingCount > (docsPerSplit*2)) {
          log.warn("Found "+missingCount+" missing values for field "+splitFieldName+
              " in shard "+shardUrl+". This can lead to poor performance when processing this shard.");
        }
      }
    }

    long _diffMs = (System.currentTimeMillis() - _startMs);

    long total = 0L;
    for (ShardSplit ss : splits) {
      total += ss.getNumHits();
    }

    long avg = Math.round((double) total / splits.size());
    log.info("Took " + _diffMs + " ms to find " + splits.size() + " splits for " +
        splitFieldName + " with avg size: " + avg + ", total: "+total);
    // warn the user about any large outliers
    long high = Math.round(avg * 1.40d);
    for (int s=0; s < splits.size(); s++) {
      ShardSplit ss = splits.get(s);
      long numHits = ss.getNumHits();
      if (numHits > high) {
        double p = (double) numHits / avg - 1d;
        long pct = Math.round(p * 100);
        log.warn("Size of split " + s + " " + ss + " is " + pct + "% larger than the avg split size " + avg +
            "; this could lead to sub-optimal job execution times.");
      }
    }

    return splits;
  }

  protected FieldStatsInfo getFieldStatsInfo(SolrClient solrClient, String shardUrl, SolrQuery solrQuery, String splitFieldName) throws IOException, SolrServerException {
    SolrQuery statsQuery = solrQuery.getCopy();
    statsQuery.setRows(0);
    statsQuery.setStart(0);
    statsQuery.set("distrib", false);
    statsQuery.remove("cursorMark");
    statsQuery.setGetFieldStatistics(splitFieldName);
    QueryResponse qr = solrClient.query(statsQuery);
    return qr.getFieldStatsInfo().get(splitFieldName);
  }

  /**
   * Finds min/max of the split field and then builds splits that span the range.
   */
  protected FieldStatsInfo appendSplits(SolrClient solrClient,
                                        List<ShardSplit> splits,
                                        SolrQuery query,
                                        String shardUrl,
                                        String splitFieldName,
                                        int splitsPerShard)
      throws IOException, SolrServerException
  {
    FieldStatsInfo fsi = getFieldStatsInfo(solrClient, shardUrl, query, splitFieldName);
    log.info("Using stats: " + fsi);

    // we just build a single big split and then call resplit on it
    ShardSplit firstSplit =
        createShardSplit(query, shardUrl, splitFieldName, fsi, null, null);
    long numHits = fsi.getCount();
    firstSplit.setNumHits(numHits);
    splits.addAll(firstSplit.reSplit(solrClient, Math.round(numHits / splitsPerShard)));

    return fsi;
  }

  protected List<ShardSplit> balanceSplits(List<ShardSplit> splits,
                                           long threshold,
                                           long docsPerSplit,
                                           SolrClient solrClient,
                                           FieldStatsInfo fsi)
      throws IOException, SolrServerException
  {
    List<ShardSplit> finalSplits = new ArrayList<ShardSplit>(splits.size());
    for (int s=0; s < splits.size(); s++) {
      ShardSplit split = splits.get(s);
      long hits = split.getNumHits();

      if (hits < threshold && s < splits.size()-1) {

        int j = s;
        do {

          long nextJoinSize = split.getNumHits() + splits.get(j+1).getNumHits();
          if (nextJoinSize > threshold)
            break;

          j += 1;
          split = join(fsi, split, splits.get(j));

          if (j+1 == splits.size())
            break;

        } while(split.getNumHits() < threshold);

        finalSplits.add(split);

        s = j;
      } else if (hits > docsPerSplit*1.8) {
        // note, recursion here
        List<ShardSplit> reSplitList = split.reSplit(solrClient, docsPerSplit);
        if (reSplitList.size() > 1) {
          reSplitList = balanceSplits(reSplitList, threshold, docsPerSplit, solrClient, fsi);
        }
        finalSplits.addAll(reSplitList);
      } else {
        finalSplits.add(split);
      }
    }

    return finalSplits;
  }

  protected void joinNonAdjacentSmallSplits(FieldStatsInfo stats, List<ShardSplit> splits, long threshold) {
    // join any small splits, if they aren't adjacent, then just OR the fq's together
    long halfDocsPerSplit = Math.round(threshold/1.8);
    for (int b=0; b < splits.size(); b++) {
      ShardSplit ss = splits.get(b);
      if (ss.getNumHits() < halfDocsPerSplit) {
        for (int j=b+1; j < splits.size(); j++) {
          ShardSplit tmp = splits.get(j);
          if (tmp.getNumHits() < halfDocsPerSplit) {
            splits.set(b, join(stats, ss, tmp));
            splits.remove(j);
            break;
          }
        }
      }
    }
  }

  public ShardSplit join(FieldStatsInfo stats, ShardSplit lhs, ShardSplit rhs) {
    ShardSplit joined = null;
    
    if (lhs.getLowerInc() != null && rhs.getLowerInc() != null) {
      String lhsLowerInc = String.valueOf(lhs.getLowerInc());
      String rhsLowerInc = String.valueOf(rhs.getLowerInc());
      ShardSplit<T> small = (rhsLowerInc.compareTo(lhsLowerInc) < 0) ? rhs : lhs;
      ShardSplit<T> big = (small == rhs) ? lhs : rhs;
      if (small.getUpper().equals(big.getLowerInc())) {
        // these splits are adjacent
        joined = createShardSplit(lhs.getQuery(), lhs.getShardUrl(), lhs.getSplitFieldName(), stats, small.getLowerInc(), big.getUpper());
      }
    }

    if (joined == null) {
      String fq = lhs.getSplitFilterQuery() + " OR "+ rhs.getSplitFilterQuery();
      joined = new FqSplit(lhs.getQuery(), lhs.getShardUrl(), lhs.getSplitFieldName(), fq);
    }

    joined.setNumHits(lhs.getNumHits() + rhs.getNumHits());

    return joined;
  }

  class FqSplit extends AbstractShardSplit<String> {
    FqSplit(SolrQuery query, String shardUrl, String rangeField, String fq) {
      super(query, shardUrl, rangeField, fq);
    }
  }

  protected abstract ShardSplit<T> createShardSplit(SolrQuery query,
                                                    String shardUrl,
                                                    String rangeField,
                                                    FieldStatsInfo stats,
                                                    T lowerInc,
                                                    T upper);
}
