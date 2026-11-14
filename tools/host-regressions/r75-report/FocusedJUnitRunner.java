import java.io.File;
import java.nio.charset.Charset;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.Test;
import org.junit.runner.*;
import org.junit.runner.notification.Failure;
import org.w3c.dom.*;

/** Report-only JUnit selector runner. The selected test source and assertions are unchanged. */
public final class FocusedJUnitRunner {
  private static String xmlSafe(String text) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length();) {
      int c = text.codePointAt(i); i += Character.charCount(c);
      if (c == 9 || c == 10 || c == 13 || c >= 0x20 && c <= 0xD7FF ||
          c >= 0xE000 && c <= 0xFFFD || c >= 0x10000 && c <= 0x10FFFF) out.appendCodePoint(c);
      else out.append(String.format("\\u%04X", c));
    }
    return out.toString();
  }
  public static void main(String[] args) throws Exception {
    if (args.length < 2) throw new IllegalArgumentException("output directory and exact selectors required");
    File out = new File(args[0]);
    if (!out.isDirectory()) throw new IllegalArgumentException("output directory must exist");
    System.out.println("java.version=" + System.getProperty("java.version"));
    System.out.println("java.vendor=" + System.getProperty("java.vendor"));
    System.out.println("defaultCharset=" + Charset.defaultCharset());
    for (String key : new String[]{"file.encoding", "sun.jnu.encoding", "native.encoding"})
      System.out.println(key + "=" + System.getProperty(key));
    System.out.println("LC_ALL=" + System.getenv("LC_ALL"));
    System.out.println("LANG=" + System.getenv("LANG"));
    Map<Class<?>, SortedSet<String>> selected = new LinkedHashMap<>();
    for (int i = 1; i < args.length; i++) {
      String[] parts = args[i].split("#", -1);
      if (parts.length > 2) throw new IllegalArgumentException("invalid selector " + args[i]);
      Class<?> klass = Class.forName(parts[0]);
      SortedSet<String> methods = selected.computeIfAbsent(klass, k -> new TreeSet<>());
      if (parts.length == 1) {
        for (java.lang.reflect.Method m : klass.getDeclaredMethods())
          if (m.isAnnotationPresent(Test.class)) methods.add(m.getName());
      } else {
        if (!klass.getDeclaredMethod(parts[1]).isAnnotationPresent(Test.class))
          throw new IllegalArgumentException("selector is not a JUnit test " + args[i]);
        methods.add(parts[1]);
      }
    }
    int total = 0, failures = 0, ignored = 0;
    for (Map.Entry<Class<?>, SortedSet<String>> suite : selected.entrySet()) {
      Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
      Element root = doc.createElement("testsuite"); doc.appendChild(root);
      root.setAttribute("name", suite.getKey().getName());
      int suiteCount = 0, suiteFailures = 0, suiteIgnored = 0;
      for (String method : suite.getValue()) {
        org.junit.runner.Result result = new JUnitCore().run(Request.method(suite.getKey(), method));
        suiteCount += result.getRunCount(); suiteFailures += result.getFailureCount();
        suiteIgnored += result.getIgnoreCount();
        Element test = doc.createElement("testcase"); root.appendChild(test);
        test.setAttribute("name", method); test.setAttribute("classname", suite.getKey().getName());
        test.setAttribute("time", Double.toString(result.getRunTime()/1000.0));
        for (Failure failure : result.getFailures()) {
          Element node = doc.createElement("failure"); test.appendChild(node);
          node.setAttribute("message", xmlSafe(String.valueOf(failure.getMessage())));
          node.setAttribute("type", failure.getException().getClass().getName());
          node.setTextContent(xmlSafe(failure.getTrace()));
          System.out.println("FAIL " + failure.getTestHeader());
          System.out.println(failure.getTrace());
        }
        if (result.getIgnoreCount() != 0) test.appendChild(doc.createElement("skipped"));
        System.out.println((result.wasSuccessful() ? "PASS " : "FAILED ") + suite.getKey().getName() + "#" + method);
      }
      root.setAttribute("tests", Integer.toString(suiteCount));
      root.setAttribute("failures", Integer.toString(suiteFailures));
      root.setAttribute("errors", "0"); root.setAttribute("skipped", Integer.toString(suiteIgnored));
      File file = new File(out, "TEST-" + suite.getKey().getName() + ".xml");
      if (file.exists()) throw new IllegalStateException("refusing to overwrite " + file);
      Transformer transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      transformer.transform(new DOMSource(doc), new StreamResult(file));
      total += suiteCount; failures += suiteFailures; ignored += suiteIgnored;
    }
    System.out.println("RESULT tests=" + total + " failures=" + failures + " ignored=" + ignored);
    System.exit(failures == 0 && ignored == 0 ? 0 : 1);
  }
}
