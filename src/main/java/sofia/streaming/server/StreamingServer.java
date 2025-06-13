package sofia.streaming.server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;



public class StreamingServer {

	
    private static final int PORT = 8888;
    //Χρήση Logger
    private static final Logger logger = Logger.getLogger("StreamingServerLogger");
    //map για απαιτούμενη ταχύτητα Mbps για κάθε ανάλυση
    private static final Map<String, Double> RESOLUTION_BITRATES = Map.of(
            "240p", 0.5,
            "360p", 1.0,
            "480p", 2.5,
            "720p", 5.0,
            "1080p", 8.0
    );
    
     

    public static void main(String[] args) {
        setupLogger();
        logger.info("Streaming Server started on port " + PORT);
        
       //ServerSocket και thread pool για  πολλούς clients
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            ExecutorService pool = Executors.newCachedThreadPool();

            while (true) {
                Socket clientSocket = serverSocket.accept();
                pool.execute(() -> handleClient(clientSocket));
            }
            
            //καταγραφή σφαλματος
        } catch (IOException e) {
            logger.severe("❌ Server error: " + e.getMessage());
        }
    }
    
    
    

    private static void handleClient(Socket socket) {   	
    	//ροες εισοδου εξοδου
        try (
            socket;
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
        	
        	//διαβαζει αρχικη εντολή από client
            String[] request = in.readLine().split(";");
            String format = request[0];
            double speed = Double.parseDouble(request[1]);
            logger.info("Received: Format=" + format + ", Speed=" + speed + " Mbps");

            //Στελνει κατάλληλα αρχεία ανάλογα format kai speed
            List<String> files = filterFiles(format, speed);
            files.forEach(out::println);
            out.println("END");

            //Αναμονή συνέχεια για νεα αιτήματα από client
            while (true) {
                String line = in.readLine();
                if (line == null) break;

                String[] parts = line.split(";");
                if (parts.length < 2) continue;

                String fileName = parts[0];
                String protocol = parts[1];

                logger.info(" Streaming file: " + fileName + " via " + protocol);
                startStreaming(fileName, protocol);
            } //οχι έλεγχος προηγούμενων ffmpeg instances 
            //πολλαπλά streams μπορεί να τρέχουν ταυτόχρονα

        } catch (IOException | NumberFormatException e) {
            logger.warning("⚠️ Client handling error: " + e.getMessage());
        }
    }
    
    
    
    
    
    //Επιστρέφει τα αρχεία από τον φάκελο videos που ταιριάζουν με το ζητούμενο
    private static List<String> filterFiles(String format, double speed) {
        File folder = new File("videos");
        File[] allFiles = folder.listFiles();
        if (allFiles == null) return List.of();

        List<String> result = new ArrayList<>();
        for (File file : allFiles) {
            String name = file.getName();
            if (!name.endsWith("." + format)) continue;

            for (String res : RESOLUTION_BITRATES.keySet()) {
                if (name.contains(res) && RESOLUTION_BITRATES.get(res) <= speed) {
                    result.add(name);
                    break;
                }
            }
        }
        return result;
    }

    
    
    
    
    private static void startStreaming(String fileName, String protocol) {
    	//Ορίζει το input path και μετατρέπει το πρωτόκολλο σε ffmpeg URL
        String inputPath = "videos/" + fileName;
        String target = switch (protocol) {
            case "UDP" -> "udp://localhost:1234";
            case "TCP" -> "tcp://localhost:1224";
            case "RTP" -> "rtp://localhost:1222";
            default -> throw new IllegalArgumentException("Invalid protocol: " + protocol);
        };

        
        // επαναλαμβανόμενη αναπαραγωγή του αρχείου και αποστολή με καταλλήλο πρωτόκολλο
        List<String> cmd = List.of(
        	    "ffmpeg", "-re", "-stream_loop", "-1", "-i", inputPath, "-c:v", "libx264", "-f", "mpegts", target
        	);
        
        
        //Εκκινεί το ffmpeg stream ως νέο εξωτερικό process αλλα δεν τερματίζει τα προγούμενα
        try {
            new ProcessBuilder(cmd).inheritIO().start();       
        } catch (IOException e) {
            logger.severe("❌ Failed to stream " + fileName + ": " + e.getMessage());
        }
    }
    
    
    //Εγκαθιστά logger, γράφει σε αρχείο και δημιουργεί τον φάκελο logs αν δεν υπάρχει
    private static void setupLogger() {
        try {
            LogManager.getLogManager().reset();
            File logDir = new File("logs");
            if (!logDir.exists()) logDir.mkdirs();
            FileHandler fh = new FileHandler("logs/server.log", true);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);
            logger.setLevel(Level.INFO);
        } catch (IOException e) {
            System.err.println(" Failed to initialize logger: " + e.getMessage());
        }
    }
}
