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
package org.apache.spark.deploy.kubernetes

import java.io.File

import scala.collection.JavaConverters._
import io.fabric8.kubernetes.api.model.{ContainerBuilder, KeyToPathBuilder, PodBuilder}
import org.apache.hadoop.security.UserGroupInformation
import org.apache.spark.deploy.kubernetes.constants._
import org.apache.spark.internal.Logging


/**
 * This is separated out from the HadoopConf steps API because this component can be reused to
 * set up the Hadoop Configuration for executors as well.
 */
private[spark] trait HadoopConfBootstrap {
 /**
  * Bootstraps a main container with the ConfigMaps mounted as volumes and an ENV variable
  * pointing to the mounted file.
  */
  def bootstrapMainContainerAndVolumes(
    originalPodWithMainContainer: PodWithMainContainer)
  : PodWithMainContainer
}

private[spark] class HadoopConfBootstrapImpl(
  hadoopConfConfigMapName: String,
  hadoopConfigFiles: Seq[File]) extends HadoopConfBootstrap with Logging{

  override def bootstrapMainContainerAndVolumes(
    originalPodWithMainContainer: PodWithMainContainer)
    : PodWithMainContainer = {
    logInfo("HADOOP_CONF_DIR defined. Mounting HDFS specific .xml files")
    val keyPaths = hadoopConfigFiles.map(file =>
      new KeyToPathBuilder()
        .withKey(file.toPath.getFileName.toString)
        .withPath(file.toPath.getFileName.toString)
      .build()).toList
    val hadoopSupportedPod = new PodBuilder(originalPodWithMainContainer.pod)
      .editSpec()
        .addNewVolume()
          .withName(HADOOP_FILE_VOLUME)
            .withNewConfigMap()
              .withName(hadoopConfConfigMapName)
              .withItems(keyPaths.asJava)
            .endConfigMap()
          .endVolume()
        .endSpec()
      .build()
    val mainContainerWithMountedHadoopConf = new ContainerBuilder(
      originalPodWithMainContainer.mainContainer)
      .addNewVolumeMount()
        .withName(HADOOP_FILE_VOLUME)
        .withMountPath(HADOOP_FILE_DIR)
        .endVolumeMount()
      .addNewEnv()
        .withName(HADOOP_CONF_DIR)
        .withValue(HADOOP_FILE_DIR)
        .endEnv()
      .addNewEnv()
        .withName(ENV_SPARK_USER)
        .withValue(UserGroupInformation.getCurrentUser.getShortUserName)
        .endEnv()
      .build()
    originalPodWithMainContainer.copy(
      pod = hadoopSupportedPod,
      mainContainer = mainContainerWithMountedHadoopConf)
  }
}