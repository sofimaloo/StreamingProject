package sofia.streaming.server;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.logging.*;
import java.util.regex.*;

public class StreamingServer {

    static Map<Integer, Double> resolutionBitrates = Map.of(
        240, 0.4,
        360, 0.75,
        480, 1.0,
        720, 2.5,
        1080, 4.5
    );

    static String[] formats = {"mp4", "avi", "mkv"};
    static String[] resolutions = {"240p", "360p", "480p", "720p", "1080p"};

    private static final Logger logger = Logger.getLogger("StreamingServerLogger");

    public static void main(String[] args) {
        setupLogger();

        File videoFolder = new File("videos");

        if (!videoFolder.exists() || !videoFolder.isDirectory()) {
            logger.severe("❌ Folder 'videos' not found!");
            return;
        }

        logger.info("📂 Checking video files in 'videos' folder...");

        File[] videoFiles = videoFolder.listFiles();
        if (videoFiles == null || videoFiles.length == 0) {
            logger.warning("⚠️ Folder 'videos' is empty.");
            return;
        }

        Map<String, Set<String>> foundFiles = new HashMap<>();

        for (File file : videoFiles) {
            String name = file.getName();
            Matcher m = Pattern.compile("^(.+)-(\\d+p)\\.(\\w+)$").matcher(name);
            if (m.matches()) {
                String title = m.group(1);
                String resolution = m.group(2);
                String format = m.group(3);
                foundFiles.putIfAbsent(title, new HashSet<>());
                foundFiles.get(title).add(resolution + "." + format);
            }
        }

        for (String title : foundFiles.keySet()) {
            Set<String> versions = foundFiles.get(title);

            int maxRes = versions.stream()
                .map(resVer -> resVer.split("\\.")[0])
                .mapToInt(r -> Integer.parseInt(r.replace("p", "")))
                .max().orElse(0);

            for (String res : resolutions) {
                int targetRes = Integer.parseInt(res.replace("p", ""));
                if (targetRes > maxRes) continue;

                for (String fmt : formats) {
                    String version = res + "." + fmt;
                    if (!versions.contains(version)) {
                        String output = title + "-" + version;
                        String outputPath = "videos/" + output;

                        String inputPath = foundFiles.get(title).stream()
                            .map(v -> "videos/" + title + "-" + v)
                            .findFirst().orElse(null);

                        if (inputPath == null) {
                            logger.warning("⚠️ No source found for: " + title);
                            continue;
                        }

                        String size = switch (res) {
                            case "240p" -> "426x240";
                            case "360p" -> "640x360";
                            case "480p" -> "854x480";
                            case "720p" -> "1280x720";
                            case "1080p" -> "1920x1080";
                            default -> throw new IllegalArgumentException("Unknown resolution");
                        };

                        List<String> cmd = List.of(
                            "C:\\Users\\user\\Downloads\\ffmpeg\\ffmpeg\\bin\\ffmpeg.exe",
                            "-y", "-i", inputPath,
                            "-s", size,
                            outputPath
                        );

                        logger.info("⚙️ Creating: " + output);
                        try {
                            Process process = new ProcessBuilder(cmd).start();
                            BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                            String line;
                            while ((line = err.readLine()) != null) {
                                logger.warning("[FFMPEG] " + line);
                            }
                            process.waitFor();
                        } catch (Exception e) {
                            logger.severe("❌ Error creating: " + output + " - " + e.getMessage());
                        }
                    }
                }
            }
        }

        try (ServerSocket serverSocket = new ServerSocket(8888)) {
            logger.info("✅ Server started on port 8888.");

            while (true) {
                logger.info("⏳ Waiting for client...");
                Socket clientSocket = serverSocket.accept();

                try (
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
                ) {
                    String input = in.readLine(); // format;speed
                    if (input == null) continue;

                    String[] parts = input.split(";");
                    String format = parts[0].toLowerCase().trim();
                    double speed = Double.parseDouble(parts[1]);

                    logger.info("📥 Client connected with speed: " + speed + " Mbps, format: " + format);

                    int maxRes = getMaxResolution(speed);
                    if (maxRes == 0) {
                        out.println("No supported resolution for your speed.");
                        out.println("END");
                        continue;
                    }

                    Pattern pattern = Pattern.compile("(.+)-(\\d+)p\\." + format);
                    File[] files = videoFolder.listFiles();

                    for (File f : files) {
                        Matcher m = pattern.matcher(f.getName());
                        if (m.matches()) {
                            int res = Integer.parseInt(m.group(2));
                            if (res <= maxRes) {
                                out.println(f.getName());
                            }
                        }
                    }

                    out.println("END");

                    String req = in.readLine(); // fileName;Protocol
                    if (req != null) {
                        String[] tokens = req.split(";");
                        if (tokens.length == 2) {
                            String fileName = tokens[0].trim();
                            String protocol = tokens[1].trim().toUpperCase();
                            String filePath = "videos/" + fileName;

                            String target = switch (protocol) {
                                case "UDP" -> "udp://localhost:1234";
                                case "TCP" -> "tcp://localhost:1234";
                                case "RTP" -> "rtp://localhost:1234";
                                default -> throw new IllegalArgumentException("Unknown protocol");
                            };

                            List<String> cmd = List.of(
                                "ffmpeg", "-re",
                                "-i", filePath,
                                "-f", "mpegts",
                                target
                            );

                            logger.info("📤 Sending file: " + fileName + " via " + protocol);
                            new ProcessBuilder(cmd).inheritIO().start();
                        }
                    }

                } catch (Exception e) {
                    logger.severe("❌ Error with client: " + e.getMessage());
                } finally {
                    clientSocket.close();
                }
            }

        } catch (IOException e) {
            logger.severe("❌ Server error: " + e.getMessage());
        }
    }

    public static int getMaxResolution(double speedMbps) {
        int max = 0;
        for (int res : resolutionBitrates.keySet()) {
            if (speedMbps >= resolutionBitrates.get(res)) {
                max = Math.max(max, res);
            }
        }
        return max;
    }

    private static void setupLogger() {
        try {
            LogManager.getLogManager().reset();
            FileHandler fh = new FileHandler("server.log", true);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);
            logger.setLevel(Level.INFO);
        } catch (IOException e) {
            System.err.println("❌ Failed to setup logger: " + e.getMessage());
        }
    }
}
