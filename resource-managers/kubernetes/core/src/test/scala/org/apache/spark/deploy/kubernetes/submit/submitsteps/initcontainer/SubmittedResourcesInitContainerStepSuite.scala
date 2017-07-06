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
package org.apache.spark.deploy.kubernetes.submit.submitsteps.initcontainer

import java.io.File
import java.util.UUID

import com.google.common.base.Charsets
import com.google.common.io.{BaseEncoding, Files}
import io.fabric8.kubernetes.api.model._

import scala.collection.JavaConverters._
import org.apache.spark.SparkFunSuite
import org.apache.spark.deploy.kubernetes.InitContainerResourceStagingServerSecretPlugin
import org.apache.spark.deploy.kubernetes.config._
import org.apache.spark.deploy.kubernetes.constants._
import org.apache.spark.deploy.kubernetes.submit.{KubernetesFileUtils, SubmittedDependencyUploader, SubmittedResourceIdAndSecret}
import org.apache.spark.util.Utils
import org.mockito.{Mock, MockitoAnnotations}
import org.mockito.Matchers.{any, eq => mockitoEq}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfter

class SubmittedResourcesInitContainerStepSuite extends SparkFunSuite with BeforeAndAfter {
  private def createTempFile(extension: String): String = {
    val dir = Utils.createTempDir()
    val file = new File(dir, s"${UUID.randomUUID().toString}.$extension")
    Files.write(UUID.randomUUID().toString, file, Charsets.UTF_8)
    file.getAbsolutePath
  }
  private val RESOURCE_SECRET_NAME = "secret"
  private val JARS_RESOURCE_ID = "jarsID"
  private val JARS_SECRET = "jarsSecret"
  private val FILES_RESOURCE_ID = "filesID"
  private val FILES_SECRET = "filesSecret"
  private val STAGING_SERVER_URI = "http://localhost:8000"
  private val SECRET_MOUNT_PATH = "/tmp"
  private val RSS_SECRET = Map(
    INIT_CONTAINER_SUBMITTED_JARS_SECRET_KEY ->
      BaseEncoding.base64().encode(JARS_SECRET.getBytes(Charsets.UTF_8)),
    INIT_CONTAINER_SUBMITTED_FILES_SECRET_KEY ->
      BaseEncoding.base64().encode(FILES_SECRET.getBytes(Charsets.UTF_8))
  ).asJava
  private val TRUSTSTORE_FILE = createTempFile(".jks")
  private val TRUSTSTORE_URI = Some(TRUSTSTORE_FILE)
  private val TRUSTSTORE_PASS = "trustStorePassword"
  private val TRUSTSTORE_TYPE = "jks"
  private val CERT_FILE = createTempFile("pem")
  private val CERT_URI = Some(CERT_FILE)

  @Mock
  private var submittedDependencyUploader: SubmittedDependencyUploader = _
  @Mock
  private var submittedResourcesSecretPlugin: InitContainerResourceStagingServerSecretPlugin = _

  before {
    MockitoAnnotations.initMocks(this)
    when(submittedDependencyUploader.uploadJars()).thenReturn(
      SubmittedResourceIdAndSecret(JARS_RESOURCE_ID, JARS_SECRET)
    )
    when(submittedDependencyUploader.uploadFiles()).thenReturn(
      SubmittedResourceIdAndSecret(FILES_RESOURCE_ID, FILES_SECRET)
    )
    when(submittedResourcesSecretPlugin.mountResourceStagingServerSecretIntoInitContainer(
      any[Container])).thenReturn(
        new ContainerBuilder().withName("mountedSecret").build())
    when(submittedResourcesSecretPlugin.addResourceStagingServerSecretVolumeToPod(
      any[Pod])).thenReturn(
      new PodBuilder()
        .withNewMetadata()
        .addToLabels("mountedSecret", "true")
        .endMetadata()
        .withNewSpec().endSpec()
        .build())
  }
  test ("testing vanilla prepareInitContainer on resources and properties") {
    val submittedResourceStep = new SubmittedResourcesInitContainerStep(
      RESOURCE_SECRET_NAME,
      STAGING_SERVER_URI,
      SECRET_MOUNT_PATH,
      false,
      None,
      None,
      None,
      None,
      submittedDependencyUploader,
      submittedResourcesSecretPlugin
    )
    val returnedInitContainer =
      submittedResourceStep.prepareInitContainer(InitContainerSpec(
        Map.empty[String, String],
        Map.empty[String, String],
        new Container(),
        new Container(),
        new Pod(),
        Seq.empty[HasMetadata]))
    assert(returnedInitContainer.initContainer.getName === "mountedSecret")
    assert(returnedInitContainer.podToInitialize.getMetadata.getLabels.asScala
      === Map("mountedSecret" -> "true"))
    assert(returnedInitContainer.initContainerDependentResources.length == 1)
    val secret = returnedInitContainer.initContainerDependentResources.head.asInstanceOf[Secret]
    assert(secret.getData === RSS_SECRET)
    assert(secret.getMetadata.getName == RESOURCE_SECRET_NAME)
    val expectedinitContainerProperties = Map(
      RESOURCE_STAGING_SERVER_URI.key -> STAGING_SERVER_URI,
      INIT_CONTAINER_DOWNLOAD_JARS_RESOURCE_IDENTIFIER.key -> JARS_RESOURCE_ID,
      INIT_CONTAINER_DOWNLOAD_JARS_SECRET_LOCATION.key ->
        s"$SECRET_MOUNT_PATH/$INIT_CONTAINER_SUBMITTED_JARS_SECRET_KEY",
      INIT_CONTAINER_DOWNLOAD_FILES_RESOURCE_IDENTIFIER.key -> FILES_RESOURCE_ID,
      INIT_CONTAINER_DOWNLOAD_FILES_SECRET_LOCATION.key ->
        s"$SECRET_MOUNT_PATH/$INIT_CONTAINER_SUBMITTED_FILES_SECRET_KEY",
      RESOURCE_STAGING_SERVER_SSL_ENABLED.key -> false.toString)
    assert(returnedInitContainer.initContainerProperties === expectedinitContainerProperties)
    assert(returnedInitContainer.additionalDriverSparkConf ===
      Map(
        EXECUTOR_INIT_CONTAINER_SECRET.key -> RESOURCE_SECRET_NAME,
        EXECUTOR_INIT_CONTAINER_SECRET_MOUNT_DIR.key -> SECRET_MOUNT_PATH))
  }

  test ("testing prepareInitContainer w/ CERT and TrustStore Files w/o SSL") {
    val submittedResourceStep = new SubmittedResourcesInitContainerStep(
      RESOURCE_SECRET_NAME,
      STAGING_SERVER_URI,
      SECRET_MOUNT_PATH,
      false,
      TRUSTSTORE_URI,
      CERT_URI,
      Some(TRUSTSTORE_PASS),
      Some(TRUSTSTORE_TYPE),
      submittedDependencyUploader,
      submittedResourcesSecretPlugin
    )
    val returnedInitContainer =
      submittedResourceStep.prepareInitContainer(InitContainerSpec(
        Map.empty[String, String],
        Map.empty[String, String],
        new Container(),
        new Container(),
        new Pod(),
        Seq.empty[HasMetadata]))
    val expectedinitContainerProperties = Map(
      RESOURCE_STAGING_SERVER_URI.key -> STAGING_SERVER_URI,
      INIT_CONTAINER_DOWNLOAD_JARS_RESOURCE_IDENTIFIER.key -> JARS_RESOURCE_ID,
      INIT_CONTAINER_DOWNLOAD_JARS_SECRET_LOCATION.key ->
        s"$SECRET_MOUNT_PATH/$INIT_CONTAINER_SUBMITTED_JARS_SECRET_KEY",
      INIT_CONTAINER_DOWNLOAD_FILES_RESOURCE_IDENTIFIER.key -> FILES_RESOURCE_ID,
      INIT_CONTAINER_DOWNLOAD_FILES_SECRET_LOCATION.key ->
        s"$SECRET_MOUNT_PATH/$INIT_CONTAINER_SUBMITTED_FILES_SECRET_KEY",
      RESOURCE_STAGING_SERVER_SSL_ENABLED.key -> false.toString) ++
    Map(
      RESOURCE_STAGING_SERVER_TRUSTSTORE_PASSWORD.key -> TRUSTSTORE_PASS,
      RESOURCE_STAGING_SERVER_TRUSTSTORE_TYPE.key -> TRUSTSTORE_TYPE,
      RESOURCE_STAGING_SERVER_TRUSTSTORE_FILE.key -> (SECRET_MOUNT_PATH + "/trustStore"),
      RESOURCE_STAGING_SERVER_CLIENT_CERT_PEM.key -> (SECRET_MOUNT_PATH + "/ssl-certificate")
    )
    assert(returnedInitContainer.initContainerProperties === expectedinitContainerProperties)

  }
}