package net.test.interceptor;

import net.test.ArgUtils;
import net.test.ArgumentsCollection;
import net.test.CommonActionArgs;
import net.test.DefaultArguments;
import net.test.GlobalArguments;

import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import javax.management.*;
import javax.management.ObjectName;

public class DiagnosticCommandInterceptor {

  public static final String NAME = "diagnostic_command";

  private static final MBeanServer diagServer = ManagementFactory.getPlatformMBeanServer();

  private static final ObjectName diagObj = createDiagObj();

  private static ObjectName createDiagObj() {
    try {
      return new ObjectName("com.sun.management:type=DiagnosticCommand");
    } catch (MalformedObjectNameException e) {
      e.printStackTrace();
    }
    return null;
  }

  private static String COMMAND = "cmd";

  private static String WHERE = "where";

  private static String WITH_GC = "with_gc";

  private static String LIMIT_OUTPUT_LINES = "limit_output_lines";

  private static List<String> KNOWN_ARGS = Arrays.asList(CommonActionArgs.IS_DATE_LOGGED, COMMAND, LIMIT_OUTPUT_LINES, WHERE, WITH_GC);

  private CommonActionArgs commonActionArgs;

  private String command;

  private final int limitForOutputLines;

  private final boolean isBefore;

  private final boolean isAfter;

  private final boolean withGC;

  private final GlobalArguments globalArguments;

  public DiagnosticCommandInterceptor(GlobalArguments globalArguments, String actionArgs, DefaultArguments defaults) {
    ArgumentsCollection parsed = ArgUtils.parseOptionalArgs(KNOWN_ARGS, actionArgs);
    this.commonActionArgs = new CommonActionArgs(parsed, defaults);
    this.command = parsed.get(COMMAND);
    this.limitForOutputLines = parsed.parseInt(LIMIT_OUTPUT_LINES, -1);
    this.withGC = parsed.parseBoolean(WITH_GC, false);
    final String where = parsed.getOrDefault(WHERE, "before");
    switch (where) {
      case "before":
        isBefore = true;
        isAfter = false;
        break;
      case "after":
        isBefore = false;
        isAfter = true;
        break;
      case "beforeAndAfter":
        isBefore = true;
        isAfter = true;
        break;
      default:
        globalArguments.getTargetStream().println("TraceAgent: (diagnostic_command / " + command + ") invalid value for `where`: " + where + ". Action is switched off!");
        isBefore = false;
        isAfter = false;
    }
    this.globalArguments = globalArguments;
  }

  private String invokeNoStringArgumentsCommand(final String operationName) {
    String result;
    try {
      result = (String) diagServer.invoke(diagObj, operationName, new Object[] {null}, new String[] {String[].class.getName()});
    } catch (InstanceNotFoundException | ReflectionException | MBeanException exception) {
      result = "ERROR: Unable to access '" + operationName + "' - " + exception;
    }
    return result;
  }

  private static String getFirstLines(String from, int n) {
    if (n == -1) {
      return from;
    } else {
      int pos = 0;
      while (n >= 0) {
        pos = from.indexOf('\n', pos);
        if (pos == -1) {
          return from;
        } else {
          pos++;
        }
        n--;
      }
      return from.substring(0, pos);
    }
  }

  private void forceGC() {
    Object obj = new Object();
    java.lang.ref.WeakReference ref = new java.lang.ref.WeakReference<Object>(obj);
    obj = null;
    invokeNoStringArgumentsCommand("GC.run");
    while (ref.get() != null) {
      globalArguments.getTargetStream().println("TraceAgent (diagnostic_command) call System.gc()");
      System.gc();
    }
  }

  @RuntimeType
  public Object intercept(@Origin Method method, @SuperCall Callable<?> callable) throws Exception {
    if (diagObj == null) {
      return callable.call();
    } else {
      if (isBefore) {
        if (withGC) {
          forceGC();
        }
        globalArguments
            .getTargetStream()
            .println(
                commonActionArgs.addPrefix(
                    "TraceAgent (diagnostic_command / "
                        + command
                        + "): at the beginning of `"
                        + method
                        + "`:\n"
                        + getFirstLines(invokeNoStringArgumentsCommand(command), limitForOutputLines)));
      }
      try {
        return callable.call();
      } finally {
        if (isAfter) {
          if (withGC) {
            forceGC();
          }
          globalArguments
              .getTargetStream()
              .println(
                  commonActionArgs.addPrefix(
                      "TraceAgent (diagnostic_command / "
                          + command
                          + "): at the end of `"
                          + method
                          + "`:\n"
                          + getFirstLines(invokeNoStringArgumentsCommand(command), limitForOutputLines)));
        }
      }
    }
  }
}
