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

package org.apache.spark.launcher;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for spark-submit command line options.
 * <p>
 * This class encapsulates the parsing code for spark-submit command line options, so that there
 * is a single list of options that needs to be maintained (well, sort of, but it makes it harder
 * to break things).
 */
class SparkSubmitOptionParser {  //命令行参数解析器

  // The following constants define the "main" name for the available options. They're defined
  // to avoid copy & paste of the raw strings where they're needed.
  //
  // The fields are not static so that they're exposed to Scala code that uses this class. See
  // SparkSubmitArguments.scala. That is also why this class is not abstract - to allow code to
  // easily use these constants without having to create dummy implementations of this class.
  protected final String CLASS = "--class"; //job的主类
  protected final String CONF = "--conf";
  protected final String DEPLOY_MODE = "--deploy-mode";  //client or cluster
  protected final String DRIVER_CLASS_PATH = "--driver-class-path";  //driver程序的类路径
  protected final String DRIVER_CORES = "--driver-cores";  //driver程序的使用CPU个数，仅限于Spark Alone模式
  protected final String DRIVER_JAVA_OPTIONS =  "--driver-java-options";
  protected final String DRIVER_LIBRARY_PATH = "--driver-library-path";  //driver程序的库路径
  protected final String DRIVER_MEMORY = "--driver-memory";  //driver程序使用内存大小
  protected final String EXECUTOR_MEMORY = "--executor-memory";  //executor的内存大小
  protected final String FILES = "--files";  //放在每一个executor工作目录的文件列表
  protected final String JARS = "--jars";  //driver依赖的第三方jar包
  protected final String KILL_SUBMISSION = "--kill";
  protected final String MASTER = "--master";  //spark://host:port;mesos://host:port;yarn;yarn-cluster;yarn-client;local
  protected final String NAME = "--name";  //Application Name
  protected final String PACKAGES = "--packages";
  protected final String PACKAGES_EXCLUDE = "--exclude-packages";
  protected final String PROPERTIES_FILE = "--properties-file";  //设置应用程序属性的文件路径
  protected final String PROXY_USER = "--proxy-user";
  protected final String PY_FILES = "--py-files";  //py文件列表
  protected final String REPOSITORIES = "--repositories";
  protected final String STATUS = "--status";
  protected final String TOTAL_EXECUTOR_CORES = "--total-executor-cores"; //executor使用的总核数

  // Options that do not take arguments.
  protected final String HELP = "--help";
  protected final String SUPERVISE = "--supervise";
  protected final String USAGE_ERROR = "--usage-error";
  protected final String VERBOSE = "--verbose";
  protected final String VERSION = "--version";

  // Standalone-only options.

  // YARN-only options.
  protected final String ARCHIVES = "--archives";
  protected final String EXECUTOR_CORES = "--executor-cores"; //每一个executor的使用的内核数
  protected final String KEYTAB = "--keytab";
  protected final String NUM_EXECUTORS = "--num-executors";  //启动的executor数量
  protected final String PRINCIPAL = "--principal";
  protected final String QUEUE = "--queue";  //提交应用程序给Yarn队列

  /**
   * This is the canonical list of spark-submit options. Each entry in the array contains the
   * different aliases for the same option; the first element of each entry is the "official"
   * name of the option, passed to {@link #handle(String, String)}.
   * <p>
   * Options not listed here nor in the "switch" list below will result in a call to
   * {@link $#handleUnknown(String)}.
   * <p>
   * These two arrays are visible for tests.
   */
  final String[][] opts = {
    { ARCHIVES },
    { CLASS },
    { CONF, "-c" },
    { DEPLOY_MODE },
    { DRIVER_CLASS_PATH },
    { DRIVER_CORES },
    { DRIVER_JAVA_OPTIONS },
    { DRIVER_LIBRARY_PATH },
    { DRIVER_MEMORY },
    { EXECUTOR_CORES },
    { EXECUTOR_MEMORY },
    { FILES },
    { JARS },
    { KEYTAB },
    { KILL_SUBMISSION },
    { MASTER },
    { NAME },
    { NUM_EXECUTORS },
    { PACKAGES },
    { PACKAGES_EXCLUDE },
    { PRINCIPAL },
    { PROPERTIES_FILE },
    { PROXY_USER },
    { PY_FILES },
    { QUEUE },
    { REPOSITORIES },
    { STATUS },
    { TOTAL_EXECUTOR_CORES },
  };

  /**
   * List of switches (command line options that do not take parameters) recognized by spark-submit.
   */
  final String[][] switches = {
    { HELP, "-h" },
    { SUPERVISE },
    { USAGE_ERROR },
    { VERBOSE, "-v" },
    { VERSION },
  };

  /**
   * Parse a list of spark-submit command line options.
   * <p>
   * See SparkSubmitArguments.scala for a more formal description of available options.
   *
   * @throws IllegalArgumentException If an error is found during parsing.
   */
  protected final void parse(List<String> args) {  //解析参数
    Pattern eqSeparatedOpt = Pattern.compile("(--[^=]+)=(.+)");

    int idx = 0;
    for (idx = 0; idx < args.size(); idx++) {
      String arg = args.get(idx);
      String value = null;

      Matcher m = eqSeparatedOpt.matcher(arg);
      if (m.matches()) {
        arg = m.group(1);
        value = m.group(2);
      }

      // Look for options with a value.
      String name = findCliOption(arg, opts);
      if (name != null) {
        if (value == null) {
          if (idx == args.size() - 1) {
            throw new IllegalArgumentException(
                String.format("Missing argument for option '%s'.", arg));
          }
          idx++;
          value = args.get(idx);
        }
        if (!handle(name, value)) {
          break;
        }
        continue;
      }

      // Look for a switch.
      name = findCliOption(arg, switches);
      if (name != null) {
        if (!handle(name, null)) {
          break;
        }
        continue;
      }

      if (!handleUnknown(arg)) {
        break;
      }
    }

    if (idx < args.size()) {
      idx++;
    }
    handleExtraArgs(args.subList(idx, args.size()));
  }

  /**
   * Callback for when an option with an argument is parsed.
   *
   * @param opt The long name of the cli option (might differ from actual command line).
   * @param value The value. This will be <i>null</i> if the option does not take a value.
   * @return Whether to continue parsing the argument list.
   */
  protected boolean handle(String opt, String value) {
    throw new UnsupportedOperationException();
  }

  /**
   * Callback for when an unrecognized option is parsed.
   *
   * @param opt Unrecognized option from the command line.
   * @return Whether to continue parsing the argument list.
   */
  protected boolean handleUnknown(String opt) {
    throw new UnsupportedOperationException();
  }

  /**
   * Callback for remaining command line arguments after either {@link #handle(String, String)} or
   * {@link #handleUnknown(String)} return "false". This will be called at the end of parsing even
   * when there are no remaining arguments.
   *
   * @param extra List of remaining arguments.
   */
  protected void handleExtraArgs(List<String> extra) {
    throw new UnsupportedOperationException();
  }

  private String findCliOption(String name, String[][] available) {
    for (String[] candidates : available) {
      for (String candidate : candidates) {
        if (candidate.equals(name)) {
          return candidates[0];
        }
      }
    }
    return null;
  }

}
