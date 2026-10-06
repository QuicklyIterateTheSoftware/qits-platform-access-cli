package eu.wohlben.qits.cli.access.report;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The JUnit XML that surefire, failsafe and vitest all write: {@code <testsuite>}s (under a
 * {@code <testsuites>} or not) of {@code <testcase>}s, each passing, or holding a {@code <failure>},
 * an {@code <error>} or a {@code <skipped>}.
 * <p>
 * Read as a stream (StAX), so a report with megabytes of {@code <system-out>} costs no memory for it.
 * No DTD and no external entity is ever resolved: the file is written by the code the run builds,
 * and is not trusted.
 * <p>
 * The counts are the test cases', not the {@code <testsuite>} attributes: surefire writes
 * {@code tests="0"} on a class whose cases all sit in {@code @Nested} classes.
 */
final class JunitXml {

    /** How a test case ended. */
    enum Outcome { PASSED, FAILED, ERRORED, SKIPPED }

    /** One {@code <testcase>}. {@code type}, {@code message} and {@code body} are its failure's or error's. */
    record Case(String classname, String name, Double seconds, Outcome outcome, String type, String message,
                String body) {
    }

    /** One {@code <testsuite>}: its name, its {@code time} when it gave one, and its cases. */
    record Suite(String name, Double seconds, List<Case> cases) {

        /** The suite's own time, else the sum of its cases'. */
        long durationMs() {
            if (seconds != null) {
                return millis(seconds);
            }
            double sum = 0;
            for (Case c : cases) {
                sum += c.seconds() == null ? 0 : c.seconds();
            }
            return millis(sum);
        }
    }

    private JunitXml() {
    }

    static List<Suite> read(Path file) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader xml = factory.createXMLStreamReader(in);
            try {
                return suites(xml);
            } finally {
                xml.close();
            }
        } catch (XMLStreamException malformed) {
            throw new IOException("not JUnit XML: " + oneLine(malformed.getMessage()), malformed);
        }
    }

    private static List<Suite> suites(XMLStreamReader xml) throws XMLStreamException, IOException {
        List<Suite> suites = new ArrayList<>();
        boolean sawRoot = false;
        while (xml.hasNext()) {
            if (xml.next() != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            String element = xml.getLocalName();
            if (!sawRoot) {
                sawRoot = true;
                if (!element.equals("testsuite") && !element.equals("testsuites")) {
                    throw new IOException("not JUnit XML: the root element is <" + element + ">");
                }
            }
            if (element.equals("testsuite")) {
                suites.add(suite(xml));
            }
        }
        if (!sawRoot) {
            throw new IOException("not JUnit XML: the file holds no element");
        }
        return suites;
    }

    /** At a {@code <testsuite>} start; returns after its end. A nested suite's cases count as this one's. */
    private static Suite suite(XMLStreamReader xml) throws XMLStreamException {
        String name = xml.getAttributeValue(null, "name");
        Double seconds = seconds(xml.getAttributeValue(null, "time"));
        List<Case> cases = new ArrayList<>();
        int depth = 1;
        while (depth > 0 && xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (xml.getLocalName().equals("testcase")) {
                    cases.add(testcase(xml));
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return new Suite(name, seconds, cases);
    }

    /** At a {@code <testcase>} start; returns after its end. The first failure, error or skip decides. */
    private static Case testcase(XMLStreamReader xml) throws XMLStreamException {
        String classname = xml.getAttributeValue(null, "classname");
        String name = xml.getAttributeValue(null, "name");
        Double seconds = seconds(xml.getAttributeValue(null, "time"));
        Outcome outcome = Outcome.PASSED;
        String type = null;
        String message = null;
        StringBuilder body = null;
        int depth = 1;
        // The element whose text is the failure's body: only that one, at its own depth.
        int bodyDepth = -1;
        while (depth > 0 && xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                String element = xml.getLocalName();
                if (depth == 2 && outcome == Outcome.PASSED) {
                    switch (element) {
                        case "failure" -> outcome = Outcome.FAILED;
                        case "error" -> outcome = Outcome.ERRORED;
                        case "skipped" -> outcome = Outcome.SKIPPED;
                        default -> {
                            continue;
                        }
                    }
                    if (outcome != Outcome.SKIPPED) {
                        type = xml.getAttributeValue(null, "type");
                        message = xml.getAttributeValue(null, "message");
                        body = new StringBuilder();
                        bodyDepth = depth;
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (depth == bodyDepth) {
                    bodyDepth = -1;
                }
                depth--;
            } else if (bodyDepth == depth && (event == XMLStreamConstants.CHARACTERS
                    || event == XMLStreamConstants.CDATA)) {
                body.append(xml.getText());
            }
        }
        return new Case(classname, name, seconds, outcome, type, message, body == null ? null : body.toString());
    }

    private static Double seconds(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value.strip().replace(",", ""));
            return Double.isFinite(parsed) && parsed >= 0 ? parsed : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    static long millis(double seconds) {
        return Math.round(seconds * 1000);
    }

    /** Whether a failure's type or message names a timeout. */
    static boolean namesTimeout(String type, String message) {
        String said = ((type == null ? "" : type) + " " + (message == null ? "" : message)).toLowerCase(Locale.ROOT);
        return said.contains("timeout") || said.contains("timed out");
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }
}
