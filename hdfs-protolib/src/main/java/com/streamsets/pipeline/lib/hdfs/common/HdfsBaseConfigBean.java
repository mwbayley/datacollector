/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.lib.hdfs.common;

import com.streamsets.datacollector.security.HadoopSecurityUtil;
import com.streamsets.datacollector.stage.HadoopConfigurationUtils;
import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.ExecutionMode;
import com.streamsets.pipeline.api.ListBeanModel;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.stage.destination.hdfs.Groups;
import com.streamsets.pipeline.stage.destination.hdfs.HadoopConfigBean;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.security.PrivilegedExceptionAction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class HdfsBaseConfigBean {
  private static final Logger LOG = LoggerFactory.getLogger(HdfsBaseConfigBean.class);

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.STRING,
      label = "Hadoop FS URI",
      description = "",
      displayPosition = 10,
      group = "HADOOP_FS"
  )
  public String hdfsUri;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.STRING,
      label = "HDFS User",
      description = "If set, the data collector will write to HDFS as this user. " +
          "The data collector user must be configured as a proxy user in HDFS.",
      displayPosition = 20,
      group = "HADOOP_FS"
  )
  public String hdfsUser;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      label = "Kerberos Authentication",
      defaultValue = "false",
      description = "",
      displayPosition = 30,
      group = "HADOOP_FS"
  )
  public boolean hdfsKerberos;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.STRING,
      defaultValue = "",
      label = "Hadoop FS Configuration Directory",
      description = "An SDC resource directory or symbolic link with HDFS configuration files core-site.xml and hdfs-site.xml",
      displayPosition = 50,
      group = "HADOOP_FS"
  )
  public String hdfsConfDir;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.MODEL,
      label = "Hadoop FS Configuration",
      description = "Additional Hadoop properties to pass to the underlying Hadoop FileSystem. These properties " +
          "have precedence over properties loaded via the 'Hadoop FS Configuration Directory' property.",
      displayPosition = 60,
      group = "HADOOP_FS"
  )
  @ListBeanModel
  public List<HadoopConfigBean> hdfsConfigs;

  protected Configuration hdfsConfiguration;
  protected UserGroupInformation loginUgi;
  protected UserGroupInformation userUgi;
  protected FileSystem fs;

  protected String getConfigBeanPrefix() {
    return "hdfsTargetConfigBean.";
  }

  protected Configuration getHadoopConfiguration(Stage.Context context, List<Stage.ConfigIssue> issues) {
    Configuration conf = new Configuration();
    conf.setClass("fs.file.impl", RawLocalFileSystem.class, FileSystem.class);
    //We handle the file system close ourselves in destroy
    //If enabled, Also this will cause issues (not allow us to rename the files on destroy call)
    // when we run a shutdown hook on app kill
    //See https://issues.streamsets.com/browse/SDC-4057
    conf.setBoolean("fs.automatic.close", false);

    // See SDC-5451, we set hadoop.treat.subject.external automatically to take advantage of HADOOP-13805
    HadoopConfigurationUtils.configureHadoopTreatSubjectExternal(conf);

    if (hdfsKerberos) {
      conf.set(
          CommonConfigurationKeys.HADOOP_SECURITY_AUTHENTICATION,
          UserGroupInformation.AuthenticationMethod.KERBEROS.name());
      try {
        conf.set(DFSConfigKeys.DFS_NAMENODE_USER_NAME_KEY, "hdfs/_HOST@" + HadoopSecurityUtil.getDefaultRealm());
      } catch (Exception ex) {
        if (!hdfsConfigs.stream().anyMatch(i -> DFSConfigKeys.DFS_NAMENODE_USER_NAME_KEY.equals(i.key))) {
          issues.add(context.createConfigIssue(
              Groups.HADOOP_FS.name(),
              null,
              Errors.HADOOPFS_28,
              ex.toString())
          );
        }
      }
    }
    if (hdfsConfDir != null && !hdfsConfDir.isEmpty()) {
      File hadoopConfigDir = new File(hdfsConfDir);
      if ((context.getExecutionMode() == ExecutionMode.CLUSTER_BATCH ||
          context.getExecutionMode() == ExecutionMode.CLUSTER_YARN_STREAMING ||
          context.getExecutionMode() == ExecutionMode.CLUSTER_MESOS_STREAMING) &&
          hadoopConfigDir.isAbsolute()
          ) {
        //Do not allow absolute hadoop config directory in cluster mode
        issues.add(
            context.createConfigIssue(
                Groups.HADOOP_FS.name(),
                getConfigBeanPrefix() + "hdfsConfDir",
                Errors.HADOOPFS_45,
                hdfsConfDir
            )
        );
      } else {
        if (!hadoopConfigDir.isAbsolute()) {
          hadoopConfigDir = new File(context.getResourcesDirectory(), hdfsConfDir).getAbsoluteFile();
        }
        if (!hadoopConfigDir.exists()) {
          issues.add(
              context.createConfigIssue(
                  Groups.HADOOP_FS.name(),
                  getConfigBeanPrefix() + "hdfsConfDir",
                  Errors.HADOOPFS_25,
                  hadoopConfigDir.getPath()
              )
          );
        } else if (!hadoopConfigDir.isDirectory()) {
          issues.add(
              context.createConfigIssue(
                  Groups.HADOOP_FS.name(),
                  getConfigBeanPrefix() + "hdfsConfDir",
                  Errors.HADOOPFS_26,
                  hadoopConfigDir.getPath()
              )
          );
        } else {
          File coreSite = new File(hadoopConfigDir, "core-site.xml");
          if (coreSite.exists()) {
            if (!coreSite.isFile()) {
              issues.add(
                  context.createConfigIssue(
                      Groups.HADOOP_FS.name(),
                      getConfigBeanPrefix() + "hdfsConfDir",
                      Errors.HADOOPFS_27,
                      coreSite.getPath()
                  )
              );
            }
            conf.addResource(new Path(coreSite.getAbsolutePath()));
          }
          File hdfsSite = new File(hadoopConfigDir, "hdfs-site.xml");
          if (hdfsSite.exists()) {
            if (!hdfsSite.isFile()) {
              issues.add(
                  context.createConfigIssue(
                      Groups.HADOOP_FS.name(),
                      getConfigBeanPrefix() + "hdfsConfDir",
                      Errors.HADOOPFS_27,
                      hdfsSite.getPath()
                  )
              );
            }
            conf.addResource(new Path(hdfsSite.getAbsolutePath()));
          }
        }
      }
    } else {
      Optional<HadoopConfigBean> fsDefaultFS = hdfsConfigs.stream()
          .filter(item -> CommonConfigurationKeys.FS_DEFAULT_NAME_KEY.equals(item.key))
          .findFirst();
      if (StringUtils.isEmpty(hdfsUri) && !fsDefaultFS.isPresent()) {
        // No URI, no config dir, and no fs.defaultFS config param
        // Avoid defaulting to writing to file:/// (SDC-5143)
        issues.add(
            context.createConfigIssue(
                Groups.HADOOP_FS.name(),
                getConfigBeanPrefix() + "hdfsUri",
                Errors.HADOOPFS_61
            )
        );
      }
    }

    for(HadoopConfigBean configBean : hdfsConfigs) {
      try {
        conf.set(
            configBean.key,
            configBean.value.get()
        );
      } catch (StageException e) {
        issues.add(
            context.createConfigIssue(
                Groups.HADOOP_FS.name(),
                getConfigBeanPrefix() + "hdfsConfigs",
                Errors.HADOOPFS_62,
                e.toString()
            )
        );
      }
    }

    return conf;
  }

  protected boolean validateHadoopFS(Stage.Context context, List<Stage.ConfigIssue> issues) {
    hdfsConfiguration = getHadoopConfiguration(context, issues);

    boolean validHapoopFsUri = true;
    // if hdfsUri is empty, we'll use the default fs uri from hdfs config. no validation required.
    if (!hdfsUri.isEmpty()) {
      if (hdfsUri.contains("://")) {
        try {
          new URI(hdfsUri);
        } catch (Exception ex) {
          issues.add(context.createConfigIssue(Groups.HADOOP_FS.name(), null, Errors.HADOOPFS_22, hdfsUri,
              ex.toString(), ex));
          validHapoopFsUri = false;
        }

        // Configured URI have precedence
        hdfsConfiguration.set(CommonConfigurationKeys.FS_DEFAULT_NAME_KEY, hdfsUri);
      } else {
        issues.add(
            context.createConfigIssue(
                Groups.HADOOP_FS.name(),
                getConfigBeanPrefix() + "hdfsUri",
                Errors.HADOOPFS_18,
                hdfsUri
            )
        );
        validHapoopFsUri = false;
      }
    } else {
      // HDFS URI is not set, we're expecting that it will be available in config files
      hdfsUri = hdfsConfiguration.get(CommonConfigurationKeys.FS_DEFAULT_NAME_KEY);
    }

    // We must have value of default.FS otherwise it's clear miss configuration
    if (hdfsUri == null || hdfsUri.isEmpty()) {
      issues.add(context.createConfigIssue(Groups.HADOOP_FS.name(), null, Errors.HADOOPFS_49));
      validHapoopFsUri = false;
    }

    StringBuilder logMessage = new StringBuilder();
    try {
      // forcing UGI to initialize with the security settings from the stage
      loginUgi = HadoopSecurityUtil.getLoginUser(hdfsConfiguration);
      userUgi = HadoopSecurityUtil.getProxyUser(
          hdfsUser,
          context,
          loginUgi,
          issues,
          Groups.HADOOP_FS.name(),
          getConfigBeanPrefix() + "hdfsUser"
      );

      if(!issues.isEmpty()) {
        return false;
      }

      if (hdfsKerberos) {
        logMessage.append("Using Kerberos");
        if (loginUgi.getAuthenticationMethod() != UserGroupInformation.AuthenticationMethod.KERBEROS) {
          issues.add(
              context.createConfigIssue(
                  Groups.HADOOP_FS.name(),
                  getConfigBeanPrefix() + "hdfsKerberos",
                  Errors.HADOOPFS_00,
                  loginUgi.getAuthenticationMethod(),
                  UserGroupInformation.AuthenticationMethod.KERBEROS
              )
          );
        }
      } else {
        logMessage.append("Using Simple");
        hdfsConfiguration.set(CommonConfigurationKeys.HADOOP_SECURITY_AUTHENTICATION,
            UserGroupInformation.AuthenticationMethod.SIMPLE.name());
      }
      if (validHapoopFsUri) {
        fs = createFileSystem();
      }
    } catch (Exception ex) {
      LOG.info("Validation Error: " + Errors.HADOOPFS_01.getMessage(), hdfsUri, ex.toString(), ex);
      issues.add(context.createConfigIssue(Groups.HADOOP_FS.name(), null, Errors.HADOOPFS_01, hdfsUri,
          String.valueOf(ex), ex));

      // We weren't able connect to the cluster and hence setting the validity to false
      validHapoopFsUri = false;
    }
    LOG.info("Authentication Config: " + logMessage);
    return validHapoopFsUri;
  }

  protected boolean validateHadoopDir(final Stage.Context context, final String configName, final String configGroup,
      String dirPathTemplate, final List<Stage.ConfigIssue> issues) {
    if (!dirPathTemplate.startsWith("/")) {
      issues.add(context.createConfigIssue(configGroup, configName, Errors.HADOOPFS_40));
      return false;
    }

    // User can opt out canary write to HDFS
    /*if(!hdfsPermissionCheck) {
      return true;
    }*/

    final AtomicBoolean ok = new AtomicBoolean(true);
    dirPathTemplate = (dirPathTemplate.isEmpty()) ? "/" : dirPathTemplate;
    try {
      final Path dir = new Path(dirPathTemplate);
      userUgi.doAs(new PrivilegedExceptionAction<Void>() {
        @Override
        public Void run() throws Exception {
          // Based on whether the target directory exists or not, we'll do different check
          if (!fs.exists(dir)) {
            // Target directory doesn't exists, we'll try to create directory a directory and then drop it
            Path workDir = dir;

            // We don't want to pollute HDFS with random directories, so we'll create exactly one directory under
            // another already existing directory on the template path. (e.g. if template is /a/b/c/d and only /a
            // exists, then we will create new dummy directory in /a during this test).
            while(!fs.exists(workDir)) {
              workDir = workDir.getParent();
            }

            // Sub-directory to be created in existing directory
            workDir = new Path(workDir, "_sdc-dummy-" + UUID.randomUUID().toString());

            try {
              if (fs.mkdirs(workDir)) {
                LOG.info("Creating dummy directory to validate permissions {}", workDir.toString());
                fs.delete(workDir, true);
                ok.set(true);
              } else {
                issues.add(context.createConfigIssue(configGroup, configName, Errors.HADOOPFS_41));
                ok.set(false);
              }
            } catch (IOException ex) {
              issues.add(context.createConfigIssue(configGroup, configName, Errors.HADOOPFS_42,
                  ex.toString()));
              ok.set(false);
            }
          } else {
            // Target directory exists, we will just create empty test file and then immediately drop it
            try {
              Path dummy = new Path(dir, "_sdc-dummy-" + UUID.randomUUID().toString());
              fs.create(dummy).close();
              fs.delete(dummy, false);
              ok.set(true);
            } catch (IOException ex) {
              issues.add(context.createConfigIssue(configGroup, configName, Errors.HADOOPFS_43,
                  ex.toString()));
              ok.set(false);
            }
          }
          return null;
        }
      });
    } catch (Exception ex) {
      issues.add(context.createConfigIssue(configGroup, configName, Errors.HADOOPFS_44,
          ex.toString()));
      ok.set(false);
    }

    return ok.get();
  }

  protected FileSystem createFileSystem() throws Exception {
    try {
      return userUgi.doAs(new PrivilegedExceptionAction<FileSystem>() {
        @Override
        public FileSystem run() throws Exception {
          return FileSystem.newInstance(new URI(hdfsUri), hdfsConfiguration);
        }
      });
    } catch (IOException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof Exception) {
        throw (Exception)cause;
      }
      throw ex;
    }
  }

}
