package sofia.streaming.client;

import java.io.*;
import java.net.Socket;

import java.util.*;
import java.util.logging.*;

public class StreamingClient {

    private static final Logger logger = Logger.getLogger("StreamingClientLogger");

    public static void main(String[] args) {
        setupLogger();

        try (Scanner scanner = new Scanner(System.in)) {
            logger.info("🔵 Streaming Client started.");

            System.out.println("Available formats: mp4, avi, mkv");
            System.out.print("Choose format: ");
            String format = scanner.nextLine().trim().toLowerCase();

            double speed = performSpeedTest();
            logger.info("Simulated speed test: " + speed + " Mbps");

            try (Socket socket = new Socket("localhost", 8888);
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                //παει format και ταχύτητα στον server
                out.println(format + ";" + speed);

                System.out.println("Available files:");
                String file;
                List<String> options = new ArrayList<>();
                while (!(file = in.readLine()).equals("END")) {
                    System.out.println(file);
                    options.add(file);
                }

                if (options.isEmpty()) {
                    logger.warning("No suitable files received from server.");
                    return;
                }

                System.out.print("Enter filename: ");
                String fileName = scanner.nextLine().trim();

                System.out.print("Enter protocol (TCP/UDP/RTP or leave blank for auto): ");
                String protocol = scanner.nextLine().trim().toUpperCase();
                if (protocol.isEmpty()) {
                    protocol = autoProtocol(fileName);
                    logger.info("Auto-selected protocol: " + protocol);
                }

                //παει filename και πρωτοκολλο στον server
                out.println(fileName + ";" + protocol);

                //Ξεκινά ffplay για να κάνει αναπαραγωγή
                String target = switch (protocol) {
                    case "UDP" -> "udp://localhost:1234";
                    case "TCP" -> "tcp://localhost:1234";
                    case "RTP" -> "rtp://localhost:1234";
                    default -> throw new IllegalArgumentException("Invalid protocol: " + protocol);
                };

                logger.info("▶️ Streaming " + fileName + " via " + protocol);

                //εΚκκίνηση του ffplay
                List<String> cmd = List.of("ffplay", "-fflags", "flush_packets", "-probesize", "5000000", "-i", target);

                new ProcessBuilder(cmd).inheritIO().start();

                logger.info("Streaming launched successfully!");

            } catch (IOException e) {
                logger.severe("❌ Error during communication: " + e.getMessage());
            }

        } catch (Exception ex) {
            logger.severe("❌ Unexpected error: " + ex.getMessage());
        }
    }

    public static String autoProtocol(String fileName) {
        if (fileName.contains("240p")) return "TCP";
        if (fileName.contains("360p") || fileName.contains("480p")) return "UDP";
        return "RTP";
    }

    public static double performSpeedTest() {
        try {
            System.out.print("Testing download speed");
            for (int i = 0; i < 5; i++) {
                Thread.sleep(1000);
                System.out.print(".");
            }
            System.out.println();
        } catch (InterruptedException ignored) {
        }
     // 4–14 Mbps
        return 4.0 + Math.random() * 10; 
    }

    private static void setupLogger() {
        try {
            LogManager.getLogManager().reset();
            File logDir = new File("logs");
            if (!logDir.exists()) logDir.mkdirs();

            FileHandler fh = new FileHandler("logs/client.log", true);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);
            logger.setLevel(Level.INFO);
        } catch (IOException e) {
            System.err.println("⚠️ Failed to initialize logger: " + e.getMessage());
        }
    }
}
