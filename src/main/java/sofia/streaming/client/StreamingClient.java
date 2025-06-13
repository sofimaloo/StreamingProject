package sofia.streaming.client;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;

public class StreamingClient {

    private static final Logger logger = Logger.getLogger(StreamingClient.class.getName());

    static {
        // Console-only logger (χωρίς αρχεία, αποφεύγουμε τα .lck errors)
        Logger rootLogger = Logger.getLogger("");
        Handler[] handlers = rootLogger.getHandlers();
        for (Handler handler : handlers) {
            rootLogger.removeHandler(handler);
        }

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.ALL);
        consoleHandler.setFormatter(new SimpleFormatter());
        rootLogger.addHandler(consoleHandler);

        logger.setLevel(Level.ALL);
    }

    public static void main(String[] args) {
        logger.info("🔵 Streaming Client started.");

        try (Scanner scanner = new Scanner(System.in)) {

            System.out.println("Available formats: mp4, avi, mkv");
            System.out.print("Choose format: ");
            String format = scanner.nextLine().trim().toLowerCase();

            double speed = 5.0; // placeholder for actual speed test
            logger.info("Assumed connection speed: " + speed + " Mbps");

            try (Socket socket = new Socket("localhost", 8888);
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                out.println(format + ";" + speed);

                System.out.println("Available files:");
                String file;
                List<String> options = new ArrayList<>();
                while (!(file = in.readLine()).equals("END")) {
                    System.out.println(" - " + file);
                    options.add(file);
                }

                if (options.isEmpty()) {
                    logger.warning("No suitable files found.");
                    return;
                }

                System.out.print("Enter filename: ");
                String fileName = scanner.nextLine().trim();

                System.out.print("Enter protocol (TCP/UDP/RTP or leave blank): ");
                String protocol = scanner.nextLine().trim().toUpperCase();
                if (protocol.isEmpty()) {
                    if (fileName.contains("240p")) protocol = "TCP";
                    else if (fileName.contains("360p") || fileName.contains("480p")) protocol = "UDP";
                    else protocol = "RTP";
                }

                out.println(fileName + ";" + protocol);
                logger.info("Requested: " + fileName + " via " + protocol);

                String target = switch (protocol) {
                    case "UDP" -> "udp://localhost:1234";
                    case "TCP" -> "tcp://localhost:1234";
                    case "RTP" -> "rtp://localhost:1234";
                    default -> throw new IllegalArgumentException("Invalid protocol");
                };

                logger.info("Launching ffplay for: " + target);
                new ProcessBuilder("ffplay", "-fflags", "nobuffer", "-i", target).inheritIO().start();

            } catch (IOException e) {
                logger.severe("❌ Communication error: " + e.getMessage());
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Fatal error", e);
        }
    }
}
