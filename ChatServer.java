import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.nio.file.*;

public class ChatServer {
    private static final int PORT = 5000;
    private static final ExecutorService threadPool = Executors.newCachedThreadPool();
    private static final Map<String, ChatRoom> chatRooms = new ConcurrentHashMap<>();
    private static final Map<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();
    private static final String SERVER_LOGS_DIR = "server_logs";
    private static final String FILE_STORAGE_DIR = "shared_files";
    private static final String VOICE_STORAGE_DIR = "voice_messages";
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB limit

    public static void main(String[] args) {
        try {
            // Create directories for logs and file storage
            createDirectories();
            
            // Initialize default chat rooms
            createDefaultRooms();

            // Start socket server
            ServerSocket serverSocket = new ServerSocket(PORT);
            System.out.println("Chat Server started on port " + PORT);
            System.out.println("Available rooms: " + String.join(", ", chatRooms.keySet()));

            while (true) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler clientHandler = new ClientHandler(clientSocket);
                threadPool.execute(clientHandler);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            threadPool.shutdown();
        }
    }

    private static void createDirectories() {
        try {
            Files.createDirectories(Paths.get(SERVER_LOGS_DIR));
            Files.createDirectories(Paths.get(FILE_STORAGE_DIR));
            Files.createDirectories(Paths.get(VOICE_STORAGE_DIR));
            System.out.println("Created necessary directories for server operation");
        } catch (IOException e) {
            System.err.println("Error creating directories: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void createDefaultRooms() {
        String[] defaultRooms = {"General", "Science", "Gaming", "Music", "Movies"};
        for (String roomName : defaultRooms) {
            chatRooms.put(roomName, new ChatRoom(roomName));
        }
    }

    static class ChatRoom {
        private final String name;
        private final Set<ClientHandler> members = ConcurrentHashMap.newKeySet();
        private final String logFile;
        private final PrintWriter logWriter;

        public ChatRoom(String name) {
            this.name = name;
            this.logFile = SERVER_LOGS_DIR + File.separator + name + "_" + 
                          LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".log";
            PrintWriter tempWriter = null;
            try {
                // Ensure the server logs directory exists
                Files.createDirectories(Paths.get(SERVER_LOGS_DIR));
                tempWriter = new PrintWriter(new FileWriter(logFile, true), true);
                tempWriter.println("--- Room '" + name + "' created/opened at " + 
                                 LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " ---");
            } catch (IOException e) {
                System.err.println("Error creating log file for room " + name + ": " + e.getMessage());
                e.printStackTrace();
            }
            this.logWriter = tempWriter;
        }

        public void broadcast(String message, ClientHandler sender) {
            for (ClientHandler client : members) {
                if (client != sender) {
                    client.sendMessage(message);
                }
            }
            // Log the message
            logMessage(message);
        }

        public void broadcastToAll(String message) {
            for (ClientHandler client : members) {
                client.sendMessage(message);
            }
            // Log the message
            logMessage(message);
        }

        public void logMessage(String message) {
            if (logWriter != null) {
                logWriter.println(message);
            }
        }

        public void addMember(ClientHandler client) {
            members.add(client);
            String joinMessage = client.username + " has joined the room";
            // Notify room members of new user
            broadcastToAll(joinMessage);
        }

        public void removeMember(ClientHandler client) {
            members.remove(client);
            String leaveMessage = client.username + " has left the room";
            // Notify room members of user leaving
            broadcastToAll(leaveMessage);
        }

        public int getMemberCount() {
            return members.size();
        }

        public List<String> getMemberNames() {
            List<String> names = new ArrayList<>();
            for (ClientHandler member : members) {
                names.add(member.username);
            }
            return names;
        }

        public void close() {
            if (logWriter != null) {
                logWriter.println("--- Room '" + name + "' closed at " + 
                                 LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " ---");
                logWriter.close();
            }
        }
    }
    
    static class ClientHandler implements Runnable {
        private Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private String username;
        private ChatRoom currentRoom;
        private static final DateTimeFormatter TIME_FORMATTER = 
            DateTimeFormatter.ofPattern("HH:mm");
        private DataInputStream dataIn;
        private DataOutputStream dataOut;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);
                dataIn = new DataInputStream(socket.getInputStream());
                dataOut = new DataOutputStream(socket.getOutputStream());

                if (!authenticate()) {
                    socket.close();
                    return;
                }

                // Default to General room
                currentRoom = chatRooms.get("General");
                currentRoom.addMember(this);

                // List available rooms
                listAvailableRooms();

                String message;
                while ((message = in.readLine()) != null) {
                    processMessage(message);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                disconnect();
            }
        }

        private void listAvailableRooms() {
            out.println("Available rooms:");
            for (ChatRoom room : chatRooms.values()) {
                out.println(room.name + " (" + room.getMemberCount() + " members)");
            }
        }

        private boolean authenticate() throws IOException {
            out.println("Enter username:");
            username = in.readLine();
            
            if (username == null || username.trim().isEmpty()) {
                out.println("Invalid username");
                return false;
            }

            if (connectedClients.containsKey(username)) {
                out.println("Username already exists");
                return false;
            }

            connectedClients.put(username, this);
            return true;
        }

        private void processMessage(String message) {
            // Trim whitespace and remove leading '/'
            message = message.trim();
            
            if (message.startsWith("/join ")) {
                // Use split to properly handle the room name
                String[] parts = message.split("\\s+", 2);
                if (parts.length > 1) {
                    String roomName = parts[1].trim();
                    joinRoom(roomName);
                } else {
                    sendMessage("Please specify a room name. Usage: /join RoomName");
                }
            } else if (message.equals("/rooms")) {
                listAvailableRooms();
            } else if (message.startsWith("/create ")) {
                // Similar split logic for create command
                String[] parts = message.split("\\s+", 2);
                if (parts.length > 1) {
                    String roomName = parts[1].trim();
                    createRoom(roomName);
                } else {
                    sendMessage("Please specify a room name. Usage: /create RoomName");
                }
            } else if (message.equals("/exit")) {
                exitCurrentRoom();
            } else if (message.equals("/members")) {
                listRoomMembers();
            } else if (message.equals("/help")) {
                showHelp();
            } else if (message.startsWith("/whisper ")) {
                sendPrivateMessage(message);
            } else if (message.equals("/sendfile")) {
                try {
                    receiveFile();
                } catch (IOException e) {
                    sendMessage("Error receiving file: " + e.getMessage());
                    e.printStackTrace();
                }
            } else if (message.startsWith("/getfile ")) {
                String[] parts = message.split("\\s+", 2);
                if (parts.length > 1) {
                    String fileName = parts[1].trim();
                    try {
                        sendFile(fileName);
                    } catch (IOException e) {
                        sendMessage("Error sending file: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    sendMessage("Please specify a file name. Usage: /getfile FileName");
                }
            } else if (message.equals("/listfiles")) {
                listAvailableFiles();
            } else if (message.equals("/sendvoice")) {
                try {
                    receiveVoiceMessage();
                } catch (IOException e) {
                    sendMessage("Error receiving voice message: " + e.getMessage());
                    e.printStackTrace();
                }
            } else if (message.startsWith("/getvoice ")) {
                String[] parts = message.split("\\s+", 2);
                if (parts.length > 1) {
                    String voiceId = parts[1].trim();
                    try {
                        sendVoiceMessage(voiceId);
                    } catch (IOException e) {
                        sendMessage("Error sending voice message: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    sendMessage("Please specify a voice message ID. Usage: /getvoice VoiceID");
                }
            } else if (message.equals("/listvoices")) {
                listAvailableVoiceMessages();
            } else {
                broadcastMessage(message);
            }
        }

        private void listAvailableFiles() {
            try {
                // Ensure the directory exists
                Files.createDirectories(Paths.get(FILE_STORAGE_DIR));
                
                File directory = new File(FILE_STORAGE_DIR);
                File[] files = directory.listFiles();
                sendMessage("Available shared files:");
                if (files != null && files.length > 0) {
                    for (File file : files) {
                        sendMessage("- " + file.getName() + " (" + formatFileSize(file.length()) + ")");
                    }
                    sendMessage("Use /getfile FileName to download a file");
                } else {
                    sendMessage("No shared files available");
                }
            } catch (Exception e) {
                sendMessage("Error listing files: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void listAvailableVoiceMessages() {
            try {
                // Ensure the directory exists
                Files.createDirectories(Paths.get(VOICE_STORAGE_DIR));
                
                File directory = new File(VOICE_STORAGE_DIR);
                File[] files = directory.listFiles();
                sendMessage("Available voice messages:");
                if (files != null && files.length > 0) {
                    for (File file : files) {
                        String fileNameWithoutExt = file.getName().replaceAll("\\.wav$", "");
                        sendMessage("- " + fileNameWithoutExt + " (" + formatFileSize(file.length()) + ")");
                    }
                    sendMessage("Use /getvoice VoiceID to download a voice message");
                } else {
                    sendMessage("No voice messages available");
                }
            } catch (Exception e) {
                sendMessage("Error listing voice messages: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        private String formatFileSize(long size) {
            final String[] units = new String[] { "B", "KB", "MB", "GB", "TB" };
            int unitIndex = 0;
            double fileSize = size;
            
            while (fileSize > 1024 && unitIndex < units.length - 1) {
                fileSize /= 1024;
                unitIndex++;
            }
            
            return String.format("%.2f %s", fileSize, units[unitIndex]);
        }

        private void receiveFile() throws IOException {
            sendMessage("READY_TO_RECEIVE_FILE");
            
            // Receive file name and size
            String fileName = in.readLine();
            
            // Check if client cancelled the file transfer
            if ("FILE_TRANSFER_CANCELLED".equals(fileName)) {
                return; // Simply return, allowing the client to continue with other commands
            }
            
            long fileSize = Long.parseLong(in.readLine());
            
            if (fileSize > MAX_FILE_SIZE) {
                sendMessage("ERROR: File too large. Maximum size is " + formatFileSize(MAX_FILE_SIZE));
                return;
            }
            
            // Ensure the file storage directory exists
            Files.createDirectories(Paths.get(FILE_STORAGE_DIR));
            
            String storedFileName = System.currentTimeMillis() + "_" + fileName;
            String filePath = FILE_STORAGE_DIR + File.separator + storedFileName;
            
            try (FileOutputStream fileOut = new FileOutputStream(filePath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesRead = 0;
                int percentCompleted = 0;
                
                while (totalBytesRead < fileSize) {
                    int remaining = (int) Math.min(buffer.length, fileSize - totalBytesRead);
                    bytesRead = dataIn.read(buffer, 0, remaining);
                    if (bytesRead == -1) break;
                    
                    fileOut.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    
                    int newPercentCompleted = (int) ((totalBytesRead * 100) / fileSize);
                    if (newPercentCompleted >= percentCompleted + 10) {
                        percentCompleted = newPercentCompleted;
                        sendMessage("File upload: " + percentCompleted + "% completed");
                    }
                }
            }
            
            sendMessage("File uploaded successfully as: " + fileName);
            
            // Notify room about the new file
            if (currentRoom != null) {
                String fileMessage = username + " shared a file: " + fileName + " (" + formatFileSize(fileSize) + ")";
                fileMessage += "\nUse /getfile " + storedFileName + " to download";
                currentRoom.broadcast(fileMessage, this);
                currentRoom.logMessage(fileMessage);
            }
        }

        private void sendFile(String fileName) throws IOException {
            // Ensure the file storage directory exists
            Files.createDirectories(Paths.get(FILE_STORAGE_DIR));
            
            File file = new File(FILE_STORAGE_DIR + File.separator + fileName);
            if (!file.exists() || !file.isFile()) {
                sendMessage("ERROR: File not found");
                return;
            }
            
            long fileSize = file.length();
            String originalFileName = fileName;
            if (fileName.contains("_")) {
                originalFileName = fileName.substring(fileName.indexOf("_") + 1);
            }
            
            sendMessage("SENDING_FILE");
            sendMessage(originalFileName);
            sendMessage(String.valueOf(fileSize));
            
            try (FileInputStream fileIn = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fileIn.read(buffer)) != -1) {
                    dataOut.write(buffer, 0, bytesRead);
                }
                dataOut.flush();
            }
            
            sendMessage("File download complete: " + originalFileName);
        }

        private void receiveVoiceMessage() throws IOException {
            sendMessage("READY_TO_RECEIVE_VOICE");
            
            // Receive voice message details
            long recordingDuration = Long.parseLong(in.readLine());
            long dataSize = Long.parseLong(in.readLine());
            
            if (dataSize > MAX_FILE_SIZE) {
                sendMessage("ERROR: Voice message too large. Maximum size is " + formatFileSize(MAX_FILE_SIZE));
                return;
            }
            
            // Ensure voice storage directory exists
            Files.createDirectories(Paths.get(VOICE_STORAGE_DIR));
            
            String voiceId = username + "_" + System.currentTimeMillis();
            String filePath = VOICE_STORAGE_DIR + File.separator + voiceId + ".wav";
            
            try (FileOutputStream fileOut = new FileOutputStream(filePath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesRead = 0;
                
                while (totalBytesRead < dataSize) {
                    int remaining = (int) Math.min(buffer.length, dataSize - totalBytesRead);
                    bytesRead = dataIn.read(buffer, 0, remaining);
                    if (bytesRead == -1) break;
                    
                    fileOut.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                }
            }
            
            int durationSeconds = (int) (recordingDuration / 1000);
            sendMessage("Voice message uploaded successfully (Duration: " + durationSeconds + " seconds)");
            
            // Notify room about the new voice message
            if (currentRoom != null) {
                String voiceMessage = username + " shared a voice message (Duration: " + durationSeconds + " seconds)";
                voiceMessage += "\nUse /getvoice " + voiceId + " to listen";
                currentRoom.broadcast(voiceMessage, this);
                currentRoom.logMessage(voiceMessage);
            }
        }

        private void sendVoiceMessage(String voiceId) throws IOException {
            // Ensure the voice storage directory exists
            Files.createDirectories(Paths.get(VOICE_STORAGE_DIR));
            
            File file = new File(VOICE_STORAGE_DIR + File.separator + voiceId + ".wav");
            if (!file.exists() || !file.isFile()) {
                sendMessage("ERROR: Voice message not found");
                return;
            }
            
            long fileSize = file.length();
            
            sendMessage("SENDING_VOICE");
            sendMessage(String.valueOf(fileSize));
            
            try (FileInputStream fileIn = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fileIn.read(buffer)) != -1) {
                    dataOut.write(buffer, 0, bytesRead);
                }
                dataOut.flush();
            }
            
            sendMessage("Voice message download complete");
        }

        private void showHelp() {
            sendMessage("Available commands:");
            sendMessage("/join [RoomName] - Join a room");
            sendMessage("/create [RoomName] - Create a new room");
            sendMessage("/rooms - List available rooms");
            sendMessage("/exit - Leave current room");
            sendMessage("/members - List members in current room");
            sendMessage("/whisper [Username] [Message] - Send private message");
            sendMessage("/sendfile - Upload and share a file");
            sendMessage("/getfile [FileName] - Download a shared file");
            sendMessage("/listfiles - List all available files");
            sendMessage("/sendvoice - Send a voice message");
            sendMessage("/getvoice [VoiceID] - Download a voice message");
            sendMessage("/listvoices - List all available voice messages");
            sendMessage("/help - Show this help menu");
        }

        private void sendPrivateMessage(String message) {
            String[] parts = message.split("\\s+", 3);
            if (parts.length < 3) {
                sendMessage("Usage: /whisper [Username] [Message]");
                return;
            }

            String targetUsername = parts[1];
            String privateMessage = parts[2];

            ClientHandler targetClient = connectedClients.get(targetUsername);
            if (targetClient == null) {
                sendMessage("User " + targetUsername + " not found.");
                return;
            }

            String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
            String formattedMessage = String.format(
                "[%s] [PRIVATE] %s whispers: %s", 
                timestamp,
                username, 
                privateMessage
            );

            targetClient.sendMessage(formattedMessage);
            sendMessage(formattedMessage);
            
            // Log private messages in both users' rooms if they are in rooms
            if (currentRoom != null) {
                currentRoom.logMessage(formattedMessage + " (to: " + targetUsername + ")");
            }
            if (targetClient.currentRoom != null && targetClient.currentRoom != currentRoom) {
                targetClient.currentRoom.logMessage(formattedMessage + " (from: " + username + ")");
            }
        }

        private void listRoomMembers() {
            if (currentRoom == null) {
                sendMessage("You are not in a room.");
                return;
            }

            List<String> members = currentRoom.getMemberNames();
            sendMessage("Members in " + currentRoom.name + ":");
            for (String member : members) {
                sendMessage("- " + member);
            }
        }

        private void exitCurrentRoom() {
            if (currentRoom == null) {
                sendMessage("You are not in a room.");
                return;
            }

            currentRoom.removeMember(this);
            currentRoom = null;
            sendMessage("You have left the room.");
            listAvailableRooms();
        }

        private void createRoom(String roomName) {
            if (chatRooms.containsKey(roomName)) {
                sendMessage("Room " + roomName + " already exists.");
                return;
            }

            ChatRoom newRoom = new ChatRoom(roomName);
            chatRooms.put(roomName, newRoom);
            sendMessage("Room " + roomName + " created successfully.");
            System.out.println("New room created: " + roomName);
        }

        private void joinRoom(String roomName) {
            ChatRoom newRoom = chatRooms.get(roomName);
            
            if (newRoom == null) {
                sendMessage("Room " + roomName + " does not exist. Use /create to make a new room.");
                return;
            }

            if (currentRoom != null) {
                currentRoom.removeMember(this);
            }

            currentRoom = newRoom;
            currentRoom.addMember(this);
            sendMessage("Joined room: " + roomName);
        }

        private void broadcastMessage(String message) {
            if (currentRoom == null) {
                sendMessage("You are not in a room. Use /join to enter a room.");
                return;
            }

            String timestamp = LocalDateTime.now()
                .format(TIME_FORMATTER);
            
            String formattedMessage = String.format(
                "[%s] %s@%s: %s", 
                timestamp, 
                username, 
                currentRoom.name,
                message
            );

            // Broadcast to other users
            currentRoom.broadcast(formattedMessage, this);
            
            // Send the message back to the sender
            sendMessage(formattedMessage);
        }

        private void disconnect() {
            try {
                connectedClients.remove(username);
                if (currentRoom != null) {
                    currentRoom.removeMember(this);
                }
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void sendMessage(String message) {
            out.println(message);
        }
    }
}
