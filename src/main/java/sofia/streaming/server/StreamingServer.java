package sofia.streaming.server;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.*;
import java.util.regex.*;

public class StreamingServer {

    static final Map<Integer, Double> resolutionBitrates = Map.of(
            240, 0.4, 360, 0.75, 480, 1.0, 720, 2.5, 1080, 4.5
    );

    static final String[] formats = {"mp4", "avi", "mkv"};
    static final String[] resolutions = {"240p", "360p", "480p", "720p", "1080p"};

    private static final Logger logger = Logger.getLogger("StreamingServerLogger");
    private static final String LOG_DIR = "logs";
    private static final String STATS_FILE = LOG_DIR + "/server_stats.csv";

    public static void main(String[] args) {
        setupLogger();
        createMissingVideos();

        try (ServerSocket serverSocket = new ServerSocket(8888)) {
            logger.info("✅ Server started on port 8888");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ClientHandler(clientSocket)).start();
            }

        } catch (IOException e) {
            logger.severe("❌ Server error: " + e.getMessage());
        }
    }

    private static void setupLogger() {
        try {
            LogManager.getLogManager().reset();
            new File(LOG_DIR).mkdirs();

            FileHandler fh = new FileHandler(LOG_DIR + "/server.log", true);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);
            logger.setLevel(Level.INFO);

            File stats = new File(STATS_FILE);
            if (!stats.exists()) {
                try (PrintWriter pw = new PrintWriter(stats)) {
                    pw.println("Timestamp,Client_IP,Filename,Protocol,BitrateMbps");
                }
            }

        } catch (IOException e) {
            System.err.println("❌ Failed to initialize logger: " + e.getMessage());
        }
    }

    private static void createMissingVideos() {
        File videoFolder = new File("videos");
        if (!videoFolder.exists()) {
            logger.severe("❌ Folder 'videos' not found!");
            return;
        }

        File[] videoFiles = videoFolder.listFiles();
        if (videoFiles == null || videoFiles.length == 0) {
            logger.warning("⚠️ Folder 'videos' is empty.");
            return;
        }

        Map<String, Set<String>> foundFiles = new HashMap<>();
        for (File file : videoFiles) {
            Matcher m = Pattern.compile("^(.+)-(\\d+p)\\.(\\w+)$").matcher(file.getName());
            if (m.matches()) {
                String title = m.group(1);
                String res = m.group(2);
                String fmt = m.group(3);
                foundFiles.putIfAbsent(title, new HashSet<>());
                foundFiles.get(title).add(res + "." + fmt);
            }
        }

        for (String title : foundFiles.keySet()) {
            Set<String> versions = foundFiles.get(title);
            int maxRes = versions.stream().map(v -> Integer.parseInt(v.split("\\.")[0].replace("p", ""))).max(Integer::compare).orElse(0);

            for (String res : resolutions) {
                int targetRes = Integer.parseInt(res.replace("p", ""));
                if (targetRes > maxRes) continue;

                for (String fmt : formats) {
                    String version = res + "." + fmt;
                    if (!versions.contains(version)) {
                        String output = "videos/" + title + "-" + version;
                        String input = foundFiles.get(title).stream().map(v -> "videos/" + title + "-" + v).findFirst().orElse(null);
                        if (input == null) continue;

                        String size = switch (res) {
                            case "240p" -> "426x240";
                            case "360p" -> "640x360";
                            case "480p" -> "854x480";
                            case "720p" -> "1280x720";
                            case "1080p" -> "1920x1080";
                            default -> throw new IllegalArgumentException("Unknown resolution");
                        };

                        List<String> cmd = List.of("ffmpeg", "-y", "-i", input, "-s", size, output);
                        try {
                            logger.info("⚙️ Creating: " + output);
                            new ProcessBuilder(cmd).inheritIO().start().waitFor();
                        } catch (Exception e) {
                            logger.severe("❌ Error creating: " + output + " - " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket client;

        public ClientHandler(Socket socket) {
            this.client = socket;
        }

        @Override
        public void run() {
            String clientIP = client.getInetAddress().getHostAddress();
            logger.info("🔗 Connected: " + clientIP);

            try (
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    PrintWriter out = new PrintWriter(client.getOutputStream(), true)
            ) {
                String input = in.readLine(); // format;speed
                if (input == null) return;

                String[] parts = input.split(";");
                String format = parts[0].trim().toLowerCase();
                double speed = Double.parseDouble(parts[1]);

                int maxRes = getMaxResolution(speed);
                if (maxRes == 0) {
                    out.println("No supported resolution for your speed.");
                    out.println("END");
                    return;
                }

                Pattern pattern = Pattern.compile("(.+)-(\\d+)p\\." + format);
                File[] files = new File("videos").listFiles();

                for (File file : files) {
                    Matcher m = pattern.matcher(file.getName());
                    if (m.matches()) {
                        int res = Integer.parseInt(m.group(2));
                        if (res <= maxRes) out.println(file.getName());
                    }
                }
                out.println("END");

                String request = in.readLine(); // filename;protocol
                if (request != null) {
                    String[] reqParts = request.split(";");
                    String filename = reqParts[0].trim();
                    String protocol = reqParts[1].trim().toUpperCase();
                    String filepath = "videos/" + filename;

                    String target = switch (protocol) {
                        case "UDP" -> "udp://localhost:1234";
                        case "TCP" -> "tcp://localhost:1234";
                        case "RTP" -> "rtp://localhost:1234";
                        default -> throw new IllegalArgumentException("Unsupported protocol: " + protocol);
                    };

                    List<String> cmd = List.of("ffmpeg", "-re", "-i", filepath, "-f", "mpegts", target);

                    logger.info("📤 Sending file " + filename + " to client " + clientIP + " via " + protocol);
                    logUsage(filename, protocol, speed, clientIP);

                    new ProcessBuilder(cmd).inheritIO().start();
                }

            } catch (Exception e) {
                logger.severe("❌ Error handling client: " + e.getMessage());
            } finally {
                try {
                    client.close();
                } catch (IOException e) {
                    logger.warning("⚠️ Failed to close socket: " + e.getMessage());
                }
            }
        }

        private void logUsage(String filename, String protocol, double speed, String ip) {
            try (PrintWriter out = new PrintWriter(new FileWriter(STATS_FILE, true))) {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                out.printf("%s,%s,%s,%s,%.2f%n", timestamp, ip, filename, protocol, speed);
            } catch (IOException e) {
                logger.warning("⚠️ Failed to log stats: " + e.getMessage());
            }
        }
    }

    private static int getMaxResolution(double speedMbps) {
        return resolutionBitrates.entrySet().stream()
                .filter(e -> speedMbps >= e.getValue())
                .map(Map.Entry::getKey)
                .max(Integer::compareTo).orElse(0);
    }
}
