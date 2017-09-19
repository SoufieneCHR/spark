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
package org.apache.spark.scheduler.cluster.kubernetes

import java.io.File

import io.fabric8.kubernetes.client.Config

import org.apache.spark.deploy.kubernetes._
import org.apache.spark.deploy.kubernetes.config._
import org.apache.spark.deploy.kubernetes.constants._
import org.apache.spark.deploy.kubernetes.submit.MountSmallFilesBootstrapImpl
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler.{ExternalClusterManager, SchedulerBackend, TaskScheduler, TaskSchedulerImpl}
import org.apache.spark.util.Utils
import org.apache.spark.SparkContext

private[spark] class KubernetesClusterManager extends ExternalClusterManager with Logging {
  override def canCreate(masterURL: String): Boolean = masterURL.startsWith("k8s")

  override def createTaskScheduler(sc: SparkContext, masterURL: String): TaskScheduler = {
    val scheduler = new KubernetesTaskSchedulerImpl(sc)
    sc.taskScheduler = scheduler
    scheduler
  }

  override def createSchedulerBackend(sc: SparkContext, masterURL: String, scheduler: TaskScheduler)
      : SchedulerBackend = {
    val sparkConf = sc.getConf
    val maybeHadoopConfigMap = sparkConf.getOption(HADOOP_CONFIG_MAP_SPARK_CONF_NAME)
    val maybeHadoopConfDir = sparkConf.getOption(HADOOP_CONF_DIR_LOC)
    val maybeDTSecretName = sparkConf.getOption(HADOOP_KERBEROS_CONF_SECRET)
    val maybeDTDataItem = sparkConf.getOption(HADOOP_KERBEROS_CONF_ITEM_KEY)
    val maybeInitContainerConfigMap = sparkConf.get(EXECUTOR_INIT_CONTAINER_CONFIG_MAP)
    val maybeInitContainerConfigMapKey = sparkConf.get(EXECUTOR_INIT_CONTAINER_CONFIG_MAP_KEY)
    val maybeSubmittedFilesSecret = sparkConf.get(EXECUTOR_SUBMITTED_SMALL_FILES_SECRET)
    val maybeSubmittedFilesSecretMountPath = sparkConf.get(
      EXECUTOR_SUBMITTED_SMALL_FILES_SECRET_MOUNT_PATH)

    val maybeExecutorInitContainerSecretName =
      sparkConf.get(EXECUTOR_INIT_CONTAINER_SECRET)
    val maybeExecutorInitContainerSecretMountPath =
      sparkConf.get(EXECUTOR_INIT_CONTAINER_SECRET_MOUNT_DIR)
    val executorInitContainerSecretVolumePlugin = for {
      initContainerSecretName <- maybeExecutorInitContainerSecretName
      initContainerSecretMountPath <- maybeExecutorInitContainerSecretMountPath
    } yield {
      new InitContainerResourceStagingServerSecretPluginImpl(
        initContainerSecretName,
        initContainerSecretMountPath)
    }
    // Only set up the bootstrap if they've provided both the config map key and the config map
    // name. The config map might not be provided if init-containers aren't being used to
    // bootstrap dependencies.
    val executorInitContainerbootStrap = for {
      configMap <- maybeInitContainerConfigMap
      configMapKey <- maybeInitContainerConfigMapKey
    } yield {
      new SparkPodInitContainerBootstrapImpl(
        sparkConf.get(INIT_CONTAINER_DOCKER_IMAGE),
        sparkConf.get(DOCKER_IMAGE_PULL_POLICY),
        sparkConf.get(INIT_CONTAINER_JARS_DOWNLOAD_LOCATION),
        sparkConf.get(INIT_CONTAINER_FILES_DOWNLOAD_LOCATION),
        sparkConf.get(INIT_CONTAINER_MOUNT_TIMEOUT),
        configMap,
        configMapKey)
    }
    val hadoopBootStrap = for {
      hadoopConfigMap <- maybeHadoopConfigMap
    } yield {
      val hadoopUtil = new HadoopUGIUtil
      val hadoopConfigurations = maybeHadoopConfDir.map(
          conf_dir => getHadoopConfFiles(conf_dir)).getOrElse(Array.empty[File])
      new HadoopConfBootstrapImpl(
        hadoopConfigMap,
        hadoopConfigurations,
        hadoopUtil
      )
    }
    val kerberosBootstrap = for {
      secretName <- maybeDTSecretName
      secretItemKey <- maybeDTDataItem
    } yield {
      new KerberosTokenConfBootstrapImpl(
        secretName,
        secretItemKey,
        Utils.getCurrentUserName)
    }
    val mountSmallFilesBootstrap = for {
      secretName <- maybeSubmittedFilesSecret
      secretMountPath <- maybeSubmittedFilesSecretMountPath
    } yield {
      new MountSmallFilesBootstrapImpl(secretName, secretMountPath)
    }
    if (maybeInitContainerConfigMap.isEmpty) {
      logWarning("The executor's init-container config map was not specified. Executors will" +
        " therefore not attempt to fetch remote or submitted dependencies.")
    }
    if (maybeInitContainerConfigMapKey.isEmpty) {
      logWarning("The executor's init-container config map key was not specified. Executors will" +
        " therefore not attempt to fetch remote or submitted dependencies.")
    }
    if (maybeHadoopConfigMap.isEmpty) {
      logWarning("The executor's hadoop config map key was not specified. Executors will" +
        " therefore not attempt to fetch hadoop configuration files.")
    }
    val kubernetesClient = SparkKubernetesClientFactory.createKubernetesClient(
        KUBERNETES_MASTER_INTERNAL_URL,
        Some(sparkConf.get(KUBERNETES_NAMESPACE)),
        APISERVER_AUTH_DRIVER_MOUNTED_CONF_PREFIX,
        sparkConf,
        Some(new File(Config.KUBERNETES_SERVICE_ACCOUNT_TOKEN_PATH)),
        Some(new File(Config.KUBERNETES_SERVICE_ACCOUNT_CA_CRT_PATH)))
    new KubernetesClusterSchedulerBackend(
        sc.taskScheduler.asInstanceOf[TaskSchedulerImpl],
        sc,
        executorInitContainerbootStrap,
        hadoopBootStrap,
        kerberosBootstrap,
        executorInitContainerSecretVolumePlugin,
        mountSmallFilesBootstrap,
        kubernetesClient)
  }

  override def initialize(scheduler: TaskScheduler, backend: SchedulerBackend): Unit = {
    scheduler.asInstanceOf[TaskSchedulerImpl].initialize(backend)
  }
  private def getHadoopConfFiles(path: String) : Array[File] = {
    def isFile(file: File) = if (file.isFile) Some(file) else None
    val dir = new File(path)
    if (dir.isDirectory) {
      dir.listFiles.flatMap { file => isFile(file) }
    } else {
      Array.empty[File]
    }
  }
}
