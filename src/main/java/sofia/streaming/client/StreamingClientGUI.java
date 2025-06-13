package sofia.streaming.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.*;
import java.net.Socket;
import java.util.*;

public class StreamingClientGUI extends Application {

    private ComboBox<String> formatBox;
    private ComboBox<String> fileBox;
    private ComboBox<String> protocolBox;
    private TextArea logArea;

    private double speedMbps;
    private final String serverHost = "localhost";
    private final int serverPort = 8888;

    @Override
    public void start(Stage primaryStage) {
        formatBox = new ComboBox<>();
        fileBox = new ComboBox<>();
        protocolBox = new ComboBox<>();
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(200);

        formatBox.getItems().addAll("mp4", "avi", "mkv");
        formatBox.setValue("mp4");

        Button speedTestBtn = new Button("1. Test Speed & Get Files");
        Button startStreamingBtn = new Button("2. Start Streaming");

        protocolBox.getItems().addAll("Auto", "TCP", "UDP", "RTP");
        protocolBox.setValue("Auto");

        speedTestBtn.setOnAction(e -> new Thread(this::performSpeedTestAndFetchFiles).start());
        startStreamingBtn.setOnAction(e -> new Thread(this::startStreaming).start());

        VBox root = new VBox(10,
                new Label("Select Format:"), formatBox,
                speedTestBtn,
                new Label("Available Files:"), fileBox,
                new Label("Select Protocol:"), protocolBox,
                startStreamingBtn,
                new Label("Logs:"), logArea
        );
        root.setPadding(new Insets(10));

        Scene scene = new Scene(root, 400, 500);
        primaryStage.setTitle("Streaming Client GUI");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void performSpeedTestAndFetchFiles() {
        log("Testing speed...");
        speedMbps = simulateSpeedTest();
        log("Speed: " + String.format("%.2f", speedMbps) + " Mbps");

        try (Socket socket = new Socket(serverHost, serverPort);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            String format = formatBox.getValue();
            out.println(format + ";" + speedMbps);

            List<String> fileList = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null && !line.equals("END")) {
                fileList.add(line);
            }

            Platform.runLater(() -> {
                fileBox.getItems().clear();
                fileBox.getItems().addAll(fileList);
                if (!fileList.isEmpty()) {
                    fileBox.setValue(fileList.get(0));
                }
                log("Received " + fileList.size() + " files from server.");
            });

        } catch (IOException e) {
            log("Error connecting to server: " + e.getMessage());
        }
    }

    private void startStreaming() {
        String fileName = fileBox.getValue();
        if (fileName == null) {
            log("Select a file first!");
            return;
        }

        String protoInput = protocolBox.getValue();
        String protocol = resolveProtocol(protoInput, fileName);

        log("Selected file: " + fileName);
        log("Protocol: " + protocol);

        try (Socket socket = new Socket(serverHost, serverPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            out.println("mp4;" + speedMbps);
            out.println(fileName + ";" + protocol);

        } catch (IOException e) {
            log("Error sending stream request: " + e.getMessage());
            return;
        }

        try {
            String target = switch (protocol) {
                case "TCP" -> "tcp://localhost:1234";
                case "UDP" -> "udp://localhost:1234";
                case "RTP" -> "rtp://localhost:1234";
                default -> "udp://localhost:1234";
            };

            List<String> cmd = List.of("ffplay", "-fflags", "nobuffer", "-i", target);
            new ProcessBuilder(cmd).inheritIO().start();
            log("Streaming started...");

        } catch (IOException e) {
            log("Failed to start ffplay: " + e.getMessage());
        }
    }

    private String resolveProtocol(String userChoice, String fileName) {
        if (!"Auto".equals(userChoice)) return userChoice;

        if (fileName.contains("240p")) return "TCP";
        if (fileName.contains("360p") || fileName.contains("480p")) return "UDP";
        return "RTP";
    }

    private void log(String message) {
        Platform.runLater(() -> logArea.appendText(message + "\n"));
    }

    private double simulateSpeedTest() {
        try {
            log("Simulating speed test for 5 seconds...");
            Thread.sleep(5000);
        } catch (InterruptedException ignored) {}
        return 4 + Math.random() * 16;
    }

    public static void main(String[] args) {
        launch(args);
    }
}