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
            
            //επιλογη format
            System.out.println("Available formats: mp4, avi, mkv");
            System.out.print("Choose format: ");
            String format = scanner.nextLine().trim().toLowerCase();

            //εικονική δοκιμή ταχυτητας
            double speed = performSpeedTest();
            logger.info("Simulated speed test: " + speed + " Mbps");
            
            //σύνδεση με  server
            try (Socket socket = new Socket("localhost", 8888);
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            	 //αποστέλλεται format και ταχύτητα στον server
                out.println(format + ";" + speed);

                //λήψη διαθέσιμων αρχείων απο server
                System.out.println("Available files:");
                String file;
                List<String> options = new ArrayList<>();
                while (!(file = in.readLine()).equals("END")) {
                    System.out.println(file);
                    options.add(file);
                }

                //έξοδος αν δεν υπάρχουν αρχεία
                if (options.isEmpty()) {
                    logger.warning("No suitable files received from server.");
                    return;
                }

                //επιλογή αρχείου από χρήστη
                System.out.print("Enter filename: ");
                String fileName = scanner.nextLine().trim();

                //επιλογή πρωτοκόλλου
                System.out.print("Enter protocol (TCP/UDP/RTP or leave blank for auto): ");
                String protocol = scanner.nextLine().trim().toUpperCase();
                if (protocol.isEmpty()) {
                    protocol = autoProtocol(fileName);
                    logger.info("Auto-selected protocol: " + protocol);
                }

                //αποστολή αρχείου και πρωτοκόλλου στον server
                out.println(fileName + ";" + protocol);
                
                //αναμονή για επιβεβαιωση απο server
                String serverReady = in.readLine();
                if (!"READY".equals(serverReady)) {
                    throw new IOException("Server not ready to stream");
                }

                //επιλογή κατάλληλης διεύθυνσης για το πρωτόκολλο
                String target = switch (protocol) {
                    case "UDP" -> "udp://localhost:1234";
                    case "TCP" -> "tcp://localhost:1224";
                    case "RTP" -> "rtp://localhost:1222";
                    default -> throw new IllegalArgumentException("Invalid protocol: " + protocol);
                };

                logger.info("▶️ Streaming " + fileName + " via " + protocol);
                
                //εκκίνηση ffplay για αναπαραγωγή του stream
                //χωρίς buffer,μικρή καθυστέρηση,χωρίς προκαταρκτική ανάλυση,πολύ μικρό αρχικό probe
                //Για να διορθωθεί Bug(αλλα δεν υπηρξε σημαντική διαφορα) 
                List<String> cmd = List.of(
                		 "ffplay",
                		    "-fflags", "nobuffer",
                		    "-flags", "low_delay",
                		   "-analyzeduration", "0",
                		    "-probesize", "32",
                		   "-buffer_size", "8192",
                		    "-i", target
                	);

                new ProcessBuilder(cmd).inheritIO().start();
                
                //επιβεβαίωση στον server ότι ο client είναι έτοιμος                
                out.println("READY");

                logger.info("Streaming launched successfully!");
                

                //δυναμική αλλαγή ποιότητας (ανάλυσης)
                while (true) {
                    System.out.print("Change resolution? (yes/no): ");
                    String ans = scanner.nextLine().trim().toLowerCase();
                    if (!ans.equals("yes")) break;

                    System.out.print("Enter new filename: ");
                    String newFile = scanner.nextLine().trim();
                    out.println(newFile + ";" + protocol);
                    logger.info("🔁 Requesting switch to: " + newFile);
                }

            } catch (IOException e) {
                logger.severe("❌ Error during communication: " + e.getMessage());
            }

        } catch (Exception ex) {
            logger.severe("❌ Unexpected error: " + ex.getMessage());
        }
    }
    
    
    //Εικονικό τεστ ταχύτητας
    static double performSpeedTest() {
        try {
            System.out.print("Testing download speed");
            for (int i = 0; i < 5; i++) {
                Thread.sleep(1000);
                System.out.print(".");
            }
            System.out.println();
        } catch (InterruptedException ignored) {
        }
        return 4.0 + Math.random() * 10;
    }
    

    //επιλογή πρωτοκόλλου ανάλογα με την ανάλυση του αρχείου
    static String autoProtocol(String fileName) {
        if (fileName.contains("240p")) return "TCP";
        if (fileName.contains("360p") || fileName.contains("480p")) return "UDP";
        return "RTP";
    }

    
    //ο logger
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
