/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gobblin.compliance.retention;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.thrift.TException;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

import gobblin.compliance.ComplianceEvents;
import gobblin.compliance.ComplianceJob;
import gobblin.compliance.utils.ProxyUtils;
import gobblin.configuration.State;
import gobblin.data.management.copy.hive.HiveDataset;
import gobblin.data.management.copy.hive.HiveDatasetFinder;
import gobblin.data.management.retention.dataset.CleanableDataset;
import gobblin.dataset.Dataset;
import gobblin.dataset.DatasetsFinder;
import gobblin.util.ExecutorsUtils;
import gobblin.util.reflection.GobblinConstructorUtils;

import static gobblin.compliance.ComplianceConfigurationKeys.GOBBLIN_COMPLIANCE_DATASET_FINDER_CLASS;


/**
 * A compliance job for compliance retention requirements.
 */
@Slf4j
public class ComplianceRetentionJob extends ComplianceJob {
  public static final List<HiveDataset> datasetList = new ArrayList<>();
  public ComplianceRetentionJob(Properties properties) {
    super(properties);
    initDatasetFinder(properties);
    try {
      Iterator<HiveDataset> datasetsIterator =
          new HiveDatasetFinder(FileSystem.newInstance(new Configuration()), properties).getDatasetsIterator();
      while (datasetsIterator.hasNext()) {
        this.datasetList.add(datasetsIterator.next());
      }
      ProxyUtils.cancelTokens(new State(properties));
    } catch (InterruptedException | TException | IOException e) {
      Throwables.propagate(e);
    }
  }

  public void initDatasetFinder(Properties properties) {
    Preconditions.checkArgument(properties.containsKey(GOBBLIN_COMPLIANCE_DATASET_FINDER_CLASS),
        "Missing required propety " + GOBBLIN_COMPLIANCE_DATASET_FINDER_CLASS);
    String finderClass = properties.getProperty(GOBBLIN_COMPLIANCE_DATASET_FINDER_CLASS);
    this.finder = GobblinConstructorUtils.invokeConstructor(DatasetsFinder.class, finderClass, new State(properties));
  }

  public void run()
      throws IOException {
    Preconditions.checkNotNull(this.finder, "Dataset finder class is not set");
    List<Dataset> datasets = this.finder.findDatasets();
    this.finishCleanSignal = Optional.of(new CountDownLatch(datasets.size()));
    for (final Dataset dataset : datasets) {
      ListenableFuture<Void> future = this.service.submit(new Callable<Void>() {
        @Override
        public Void call()
            throws Exception {
          if (dataset instanceof CleanableDataset) {
            ((CleanableDataset) dataset).clean();
          } else {
            log.warn(
                "Not an instance of " + CleanableDataset.class + " Dataset won't be cleaned " + dataset.datasetURN());
          }
          return null;
        }
      });
      Futures.addCallback(future, new FutureCallback<Void>() {
        @Override
        public void onSuccess(@Nullable Void result) {
          ComplianceRetentionJob.this.finishCleanSignal.get().countDown();
          log.info("Successfully cleaned: " + dataset.datasetURN());
        }

        @Override
        public void onFailure(Throwable t) {
          ComplianceRetentionJob.this.finishCleanSignal.get().countDown();
          log.warn("Exception caught when cleaning " + dataset.datasetURN() + ".", t);
          ComplianceRetentionJob.this.throwables.add(t);
          ComplianceRetentionJob.this.eventSubmitter.submit(ComplianceEvents.Retention.FAILED_EVENT_NAME, ImmutableMap
              .of(ComplianceEvents.FAILURE_CONTEXT_METADATA_KEY, ExceptionUtils.getFullStackTrace(t),
                  ComplianceEvents.DATASET_URN_METADATA_KEY, dataset.datasetURN()));
        }
      });
    }
  }

  @Override
  public void close()
      throws IOException {
    try {
      if (this.finishCleanSignal.isPresent()) {
        this.finishCleanSignal.get().await();
      }
      if (!this.throwables.isEmpty()) {
        for (Throwable t : this.throwables) {
          log.error("Failed clean due to ", t);
        }
        throw new RuntimeException("Retention failed for one or more datasets");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Not all datasets finish retention", e);
    } finally {
      ExecutorsUtils.shutdownExecutorService(this.service, Optional.of(log));
      this.closer.close();
    }
  }

  /**
   * Generates retention metrics for the instrumentation of this class.
   */
  @Override
  protected void regenerateMetrics() {
    // TODO
  }
}
