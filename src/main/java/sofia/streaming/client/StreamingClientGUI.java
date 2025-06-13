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

    private JComboBox<String> formatBox;
    private JComboBox<String> fileBox;
    private JComboBox<String> protocolBox;
    private JButton connectButton;
    private JTextArea outputArea;

    private static final Logger logger = Logger.getLogger("StreamingClientGUILogger");

    public StreamingClientGUI() {
        super("Streaming Client GUI");
        setupLogger();
        setupUI();
    }

    private void setupUI() {
        setSize(500, 300);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel topPanel = new JPanel(new GridLayout(4, 2));
        formatBox = new JComboBox<>(new String[]{"mp4", "avi", "mkv"});
        fileBox = new JComboBox<>();
        protocolBox = new JComboBox<>(new String[]{"Auto", "TCP", "UDP", "RTP"});
        connectButton = new JButton("Start Streaming");

        topPanel.add(new JLabel("Format:"));
        topPanel.add(formatBox);
        topPanel.add(new JLabel("Protocol:"));
        topPanel.add(protocolBox);
        topPanel.add(new JLabel("File:"));
        topPanel.add(fileBox);
        topPanel.add(new JLabel(""));
        topPanel.add(connectButton);

        add(topPanel, BorderLayout.NORTH);

        outputArea = new JTextArea();
        outputArea.setEditable(false);
        add(new JScrollPane(outputArea), BorderLayout.CENTER);

        connectButton.addActionListener(this::connectToServer);
    }

    private void connectToServer(ActionEvent e) {
        String format = formatBox.getSelectedItem().toString();
        String protocolInput = protocolBox.getSelectedItem().toString();

        double speed = performSpeedTest();
        logger.info("[GUI] Speed test: " + speed + " Mbps");

        try (Socket socket = new Socket("localhost", 8888);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println(format + ";" + speed);
            outputArea.append("Available files:\n");
            List<String> options = new ArrayList<>();

            String line;
            while (!(line = in.readLine()).equals("END")) {
                options.add(line);
                outputArea.append(line + "\n");
            }

            fileBox.removeAllItems();
            for (String file : options) fileBox.addItem(file);

            String selectedFile = (String) JOptionPane.showInputDialog(this, "Choose file:",
                    "Select", JOptionPane.PLAIN_MESSAGE, null,
                    options.toArray(), options.get(0));

            if (selectedFile == null) return;
            fileBox.setSelectedItem(selectedFile);

            String protocol = protocolInput.equals("Auto") ? autoProtocol(selectedFile) : protocolInput;
            out.println(selectedFile + ";" + protocol);

            String target = switch (protocol) {
                case "UDP" -> "udp://localhost:1234";
                case "TCP" -> "tcp://localhost:1234";
                case "RTP" -> "rtp://localhost:1234";
                default -> throw new IllegalArgumentException("Invalid protocol: " + protocol);
            };

            logger.info("▶️ Launching ffplay: " + selectedFile + " via " + protocol);
            new ProcessBuilder("ffplay", "-fflags", "nobuffer", "-i", target).inheritIO().start();

            logClientStats(selectedFile, protocol, speed);

        } catch (Exception ex) {
            logger.severe("❌ GUI error: " + ex.getMessage());
            outputArea.append("Error: " + ex.getMessage());
        }
    }

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

    private double performSpeedTest() {
        try {
            System.out.print("Testing speed");
            for (int i = 0; i < 5; i++) {
                Thread.sleep(1000);
                System.out.print(".");
            }
            System.out.println();
        } catch (InterruptedException ignored) {}
        return 4.0 + Math.random() * 10;
    }

    private String autoProtocol(String fileName) {
        if (fileName.contains("240p")) return "TCP";
        if (fileName.contains("360p") || fileName.contains("480p")) return "UDP";
        return "RTP";
    }

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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new StreamingClientGUI().setVisible(true));
    }
}
