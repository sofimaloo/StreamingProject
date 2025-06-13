package sofia.streaming.client;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.logging.*;

public class StreamingClientGUI extends JFrame {

    private static final long serialVersionUID = 1L;
    
    //components
    private JComboBox<String> formatBox;
    private JComboBox<String> fileBox;
    private JComboBox<String> protocolBox;
    private JButton connectButton;
    private JTextArea outputArea;
    

    private static final Logger logger = Logger.getLogger("StreamingClientGUILogger");

    private volatile Process ffplayProcess = null;
    private volatile boolean adaptiveRunning = false;
    private String selectedFile;
    private String selectedFormat;
    private String selectedProtocol;

    
    
    
    public StreamingClientGUI() {
        super("Streaming Client GUI");
        setupLogger();
        setupUI();
    }

    
       //Δημιουργια UI του client
    private void setupUI() {
        setSize(600, 350);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel topPanel = new JPanel(new GridLayout(4, 2));
        
        //επιλογές του χρήστη
        formatBox = new JComboBox<>(new String[]{"mp4", "avi", "mkv"});
        fileBox = new JComboBox<>();
        protocolBox = new JComboBox<>(new String[]{"Auto", "TCP", "UDP", "RTP"});
        connectButton = new JButton("Start Streaming");
        
        
        //επιλογές στο Panel
        topPanel.add(new JLabel("Format:"));
        topPanel.add(formatBox);
        topPanel.add(new JLabel("Protocol:"));
        topPanel.add(protocolBox);
        topPanel.add(new JLabel("File:"));
        topPanel.add(fileBox);
        topPanel.add(new JLabel(""));
        topPanel.add(connectButton);

        add(topPanel, BorderLayout.NORTH);

        //Σημειίο για έξοδο των Logs
        outputArea = new JTextArea();
        outputArea.setEditable(false);
        add(new JScrollPane(outputArea), BorderLayout.CENTER);

        connectButton.addActionListener(this::connectToServer);
    }

    
    //Σύνδεση με server και ξεκινημα ροής
    private void connectToServer(ActionEvent e) {
        selectedFormat = formatBox.getSelectedItem().toString();
        selectedProtocol = protocolBox.getSelectedItem().toString();

        //Τεστ ταχύτητας
        double speed = performSpeedTest();
        logger.info("[GUI] Initial speed test: " + speed + " Mbps");

        try (Socket socket = new Socket("localhost", 8888);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

        	//αποστολή τεστ ταχύτητας στον server
            out.println(selectedFormat + ";" + speed);
            outputArea.append("Available files:\n");
            List<String> options = new ArrayList<>();

            String line;
            while (!(line = in.readLine()).equals("END")) {
                options.add(line);
                outputArea.append(line + "\n");
            }

            
            //ενημέρωαση dropdown
            fileBox.removeAllItems();
            for (String file : options) fileBox.addItem(file);
            
            //επιλογή αρχείου απο χρήστη
            selectedFile = (String) JOptionPane.showInputDialog(this, "Choose file:",
                    "Select", JOptionPane.PLAIN_MESSAGE, null,
                    options.toArray(), options.get(0));

            if (selectedFile == null) return;
            fileBox.setSelectedItem(selectedFile);

            //επιλογή πρωτοκόλλου
            if (selectedProtocol.equals("Auto"))
                selectedProtocol = autoProtocol(selectedFile);

            out.println(selectedFile + ";" + selectedProtocol);
            
            //Αναπαραγωγή
            startFFplay(selectedProtocol);
            logClientStats(selectedFile, selectedProtocol, speed);

            startAdaptiveThread(out, selectedFormat, selectedProtocol);

        } catch (Exception ex) {
            logger.severe("❌ GUI error: " + ex.getMessage());
            outputArea.append("Error: " + ex.getMessage());
        }
    }

    
    //Ξεκινά-τερματίζει ffplay για το σωστό πρωτόκολλο
    private void startFFplay(String protocol) throws IOException {
    	
    	if (ffplayProcess != null && ffplayProcess.isAlive()) {
            ffplayProcess.destroy();
            logger.info("Stopped previous ffplay instance");
        }

        String target = switch (protocol) {
            case "UDP" -> "udp://localhost:1234";
            case "TCP" -> "tcp://localhost:1224";
            case "RTP" -> "rtp://localhost:1222";
            default -> throw new IllegalArgumentException("Invalid protocol: " + protocol);
        };
        
        
        //Εκκίνηση ffplay με low latency flags
        List<String> cmd = List.of(
                "ffplay",
                "-fflags", "nobuffer",
                "-flags", "low_delay",
                "-probesize", "32",
                "-analyzeduration", "0",
                "-buffer_size", "8192",
                "-i", target
            );
        
        ffplayProcess = new ProcessBuilder(cmd).inheritIO().start();
        logger.info(" ffplay launched for: " + target);
    }
    
    //adaptive bitrate logic σε νέο thread
    private void startAdaptiveThread(PrintWriter out, String format, String protocol) {
        adaptiveRunning = true;
        new Thread(() -> {
            double lastSpeed = -1;
            while (adaptiveRunning) {
                try {
                	//ανα 10 δευτερολεπτα
                    Thread.sleep(10000);
                    double newSpeed = performSpeedTest();
                    if (Math.abs(newSpeed - lastSpeed) > 1.0) {
                        String newResolution = getResolutionForSpeed(newSpeed);
                        String newFile = selectedFile.replaceAll("-(240|360|480|720|1080)p", "-" + newResolution);
                        String newProtocol = autoProtocol(newFile);

                        logger.info("Adaptive switch to: " + newFile);
                        out.println(newFile + ";" + newProtocol);
                        startFFplay(newProtocol);
                        logClientStats(newFile, newProtocol, newSpeed);
                        lastSpeed = newSpeed;
                    }
                } catch (Exception ex) {
                    logger.warning("Adaptive thread error: " + ex.getMessage());
                }
            }
        }).start();
    }
    
    
    //Επιστροφή ανάλυσης που αντιστοιχεί σε δεδομένη ταχύτητα
    private String getResolutionForSpeed(double speed) {
        if (speed < 1.0) return "240p";
        else if (speed < 2.5) return "360p";
        else if (speed < 5.0) return "480p";
        else if (speed < 8.0) return "720p";
        else return "1080p";
    }

    //εικονική δοκιμή ταχύτητας δικτύου
    private double performSpeedTest() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {}
        return 4.0 + Math.random() * 10;
    }

    //επιλογή πρωτοκόλλου με βάση ανάλυση αρχείου
    private String autoProtocol(String fileName) {
        if (fileName.contains("240p")) return "TCP";
        if (fileName.contains("360p") || fileName.contains("480p")) return "UDP";
        return "RTP";
    }

    
    //καταγραφή στατιστικών σε αρχείο CSV
    private void logClientStats(String file, String protocol, double speed) {
        File statsFile = new File("logs/stats_client.csv");
        try {
            if (!statsFile.exists()) {
                try (PrintWriter writer = new PrintWriter(statsFile)) {
                    writer.println("Timestamp,Filename,Protocol,Speed(Mbps)");
                }
            }
            try (PrintWriter out = new PrintWriter(new FileWriter(statsFile, true))) {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                out.printf("%s,%s,%s,%.2f%n", timestamp, file, protocol, speed);
            }
        } catch (IOException e) {
            logger.warning("⚠️ Failed to write client stats: " + e.getMessage());
        }
    }

    //ο Logger GUI
    private void setupLogger() {
        try {
            LogManager.getLogManager().reset();
            File logDir = new File("logs");
            if (!logDir.exists()) logDir.mkdirs();
            FileHandler fh = new FileHandler("logs/gui.log", true);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);
            logger.setLevel(Level.INFO);
        } catch (IOException e) {
            System.err.println("⚠️ Failed to initialize GUI logger: " + e.getMessage());
        }
    }
    
    
    //εκκίνηση
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new StreamingClientGUI().setVisible(true));
    }
}
