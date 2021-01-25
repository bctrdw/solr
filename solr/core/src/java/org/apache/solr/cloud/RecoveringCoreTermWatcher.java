/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud;

import org.apache.solr.client.solrj.cloud.ShardTerms;
import org.apache.solr.common.ParWork;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.core.SolrCore;
import org.apache.solr.logging.MDCLoggingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Start recovery of a core if its term is less than leader's term
 */
public class RecoveringCoreTermWatcher implements ZkShardTerms.CoreTermWatcher, Closeable {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final CoreDescriptor coreDescriptor;
  private final CoreContainer coreContainer;
  // used to prevent the case when term of other replicas get changed, we redo recovery
  // the idea here is with a specific term of a replica, we only do recovery one
  private final AtomicLong lastTermDoRecovery;
  private volatile boolean closed;

  RecoveringCoreTermWatcher(CoreDescriptor coreDescriptor, CoreContainer coreContainer) {
    this.coreDescriptor = coreDescriptor;
    this.coreContainer = coreContainer;
    this.lastTermDoRecovery = new AtomicLong(-1);
  }

  @Override
  public boolean onTermChanged(ShardTerms terms) {
    if (coreContainer.isShutDown()) return false;
    MDCLoggingContext.setCoreName(coreDescriptor.getName());

    try {
      if (closed) {
        return false;
      }
      String coreName = coreDescriptor.getName();
      if (terms.haveHighestTermValue(coreName)) return true;
      if (terms.getTerm(coreName) != null && lastTermDoRecovery.get() < terms.getTerm(coreName)) {
        log.info("Start recovery on {} because core's term is less than leader's term", coreName);
        lastTermDoRecovery.set(terms.getTerm(coreName));
        LeaderElector leaderElector = coreContainer.getZkController().getLeaderElector(coreName);
        if (leaderElector != null) {
          leaderElector.retryElection(false);
        }
        try (SolrCore solrCore = coreContainer.getCore(coreDescriptor.getName())) {
          solrCore.getUpdateHandler().getSolrCoreState().doRecovery(solrCore.getCoreContainer(), solrCore.getCoreDescriptor());
        }
      }
    } catch (Exception e) {
      ParWork.propagateInterrupt(e);
      if (log.isInfoEnabled()) {
        log.info("Failed to watch term of core {}", coreDescriptor.getName(), e);
      }
      return false;
    } finally {
      MDCLoggingContext.clear();
    }

    return true;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    RecoveringCoreTermWatcher that = (RecoveringCoreTermWatcher) o;

    return coreDescriptor.getName().equals(that.coreDescriptor.getName());
  }

  @Override
  public int hashCode() {
    return coreDescriptor.getName().hashCode();
  }

  @Override
  public void close() {
   this.closed = true;
  }
}
