import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.zip.GZIPOutputStream;

import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;

/**
 * Représente une instance de serveur web écoutant sur un port spécifique.
 * Gère le traitement des requêtes HTTP/1.1 (GET), le listage de répertoires,
 * l'exécution de scripts embarqués, l'envoi de headers, les ETags et la mise en cache.
 */
public class Site {
    private static final String GREEN = "\u001b[32m";
    private static final String RESET = "\u001b[0m";
    private ServerSocket serverSocket;
    private Socket client;
    private String documentRoot = "/var/www2";
    private String defaultIndex = "index.html";
    private String acceslog = "/var/log/myweb/access.log";
    private String errorlog = "/var/log/myweb/error.log";

    // ########## Constructeur ##########
    /**
     * Initialise un site web avec les paramètres de configuration spécifiés.
     *
     * @param port Le port d'écoute du serveur. Si invalide (hors plage 0-65535), 8080 est utilisé par défaut.
     * @param documentRoot Le dossier racine contenant les ressources du site.
     * @param defaultIndex Le fichier d'index par défaut du site.
     * @param acceslog Le chemin du fichier de log pour les accès.
     * @param errorlog Le chemin du fichier de log pour les erreurs.
     * @throws IOException Si une erreur d'entrée/sortie survient lors de la création du socket serveur.
     */
    public Site(int port, String documentRoot, String defaultIndex, String acceslog, String errorlog) throws IOException {
        if (port < 0 || port > 65535) port = 8080;

        this.serverSocket = new ServerSocket(port);

        if (documentRoot != null) this.documentRoot = documentRoot;
        if (defaultIndex != null) this.defaultIndex = defaultIndex;
        if (acceslog != null) this.acceslog = acceslog;
        if (errorlog != null) this.errorlog = errorlog;
    }

    // ########## Méthodes ##########
    /**
     * Exécute une commande système via un interpréteur spécifique et retourne sa sortie standard.
     *
     * @param interpreteur L'interpréteur de commandes à utiliser (ex. "/bin/bash" ou "/bin/sh").
     * @param commande La commande à exécuter.
     * @return La sortie de la commande sous forme de chaîne de caractères, ou un message d'erreur.
     */
    public String executer(String interpreteur, String commande) {
        ProcessBuilder processBuilder = new ProcessBuilder(interpreteur, "-c", commande);
        try {
            Process process = processBuilder.start();
            log("Commande exécutée avec l'interpreteur " + interpreteur + " : " + commande, false);
            // Lecture de la sortie de la commande
            BufferedReader outputCommande = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = outputCommande.readLine()) != null) {
                output.append(line).append("\n");
            }
            return output.toString();
        } catch (IOException e) {
            return "Erreur lors de l'exécution de la commande: " + e.getMessage();
        }
    }

    /**
     * Démarre le serveur web dans un nouveau thread indépendant pour commencer à écouter
     * et traiter les requêtes entrantes.
     */
    public void startServer() {
        Thread thread = new Thread(() -> {
            try {
                this.handler();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        thread.start();
    }

    /**
     * Boucle principale de traitement du serveur. Attend les connexions des clients,
     * lit les requêtes HTTP, résout les chemins de fichiers, gère les en-têtes (comme If-Modified-Since),
     * exécute les scripts inclus dans les pages HTML, et renvoie la réponse HTTP appropriée.
     *
     * @throws IOException Si une erreur d'entrée/sortie survient lors de la communication avec le client.
     */
    private void handler() throws IOException {
        System.out.println("Site " + getPort() + " en attente de connexion... Allez sur http://localhost:" + getPort() + " pour accéder au site.");

        while (true) {
            client = serverSocket.accept();

            log("Connexion entrante de " + client.getRemoteSocketAddress(), false);

            BufferedReader requete = new BufferedReader(new InputStreamReader(client.getInputStream()));

            StringBuilder recu = new StringBuilder();
            String ligne;

            // On lit tant qu'on n'a pas atteint une ligne vide (fin des headers HTTP)
            // On ajoute le isEmpty() pour éviter les problèmes de lecture infinie si le client ne ferme pas la connexion (keep-alive)
            while ((ligne = requete.readLine()) != null && !ligne.isEmpty()) {
                recu.append(ligne).append("\n");
            }

            // Le .trim() pour retiré le \n à la fin de la requête
            log(recu.toString().trim(), false);

            String[] recuTab = recu.toString().split(" ");

            // Le fichier
            boolean fichierValide = true;
            String fichier = "";
            if (recuTab.length < 2) {
                fichierValide = false;
                log("Requête mal formée: " + recu, true);
            } else {
                fichier = recuTab[1];
                if (fichier.equals("/")) fichier = documentRoot + "/" + defaultIndex;
                else fichier = documentRoot + fichier;
            }

            boolean isStatusRequest = recuTab.length >= 2 && recuTab[1].equals("/status");
            boolean isForbidden = recuTab.length >= 2 && recuTab[1].contains("..");
            boolean isDirectory = new File(fichier).isDirectory();

            String eTag = "";
            boolean isModifiedSince = false;
            long lastModifiedMs = 0;

            if (!isStatusRequest && !isDirectory && !isForbidden) {
                // On vérifie si le fichier existe
                try {
                    new DataInputStream(new FileInputStream(fichier));
                } catch (FileNotFoundException e) {
                    fichierValide = false;
                }

                if (fichierValide) {
                    // 2. On génère l'ETag
                    try {
                        eTag = getETag(fichier);
                    } catch (Exception e) {
                        // Si le fichier n'existe pas ou qu'il y a une erreur de lecture, on considère que le fichier n'est pas valide
                        fichierValide = false;
                    }

                    // On récupère la date de la dernière modification du fichier
                    Date lastModifiedDate = new Date(new File(fichier).lastModified());
                    lastModifiedMs = lastModifiedDate.getTime() / 1000 * 1000; // On arrondit à la seconde pour éviter les problèmes de comparaison de date (le client n'envoie pas les millisecondes)
                    lastModifiedDate = new Date(lastModifiedMs);
                    // Si le client a envoyé un If-None-Match avec l'ETag du fichier, alors on considère que le fichier n'a pas été modifié depuis la dernière fois qu'il a été demandé
                    if (recu.toString().contains("If-Modified-Since: ")) {

                         // On récupère la date envoyée par le client dans le header If-Modified-Since
                        Pattern pattern = Pattern.compile("If-Modified-Since: (.*)");
                        Matcher matcher = pattern.matcher(recu.toString());
                        Date clientDate = null;
                        if (matcher.find()) {
                            String dateStr = matcher.group(1).trim();
                            try {
                                clientDate = new Date(dateStr);
                            } catch (Exception e) {
                                log("Erreur lors de la lecture de la date du fichier demander par le client: " + e.getMessage(), true);
                            }
                        }

                        // Si la date du client est égale ou supérieure à la date de la dernière modification du fichier, alors on considère que le fichier n'a pas été modifié depuis la dernière fois qu'il a été demandé
                        if (clientDate != null && !clientDate.before(lastModifiedDate)) {
                            isModifiedSince = true;
                        }
                    }
                }
            }

            // On utilise un writer pour les headers (texte)
            PrintWriter writer = new PrintWriter(client.getOutputStream(), false); // false = flush manuel

            if (isStatusRequest) {
                String contenu = getSystemStatus();
                envoyerHeader(writer, "200 OK", "text/html; charset=UTF-8", contenu.getBytes().length, false, "", 0, false);
                writer.print(contenu);
                log("200 OK: Status du serveur (HTML) envoyé au client", false);
            } else if (isDirectory) { // Le fichier est un dossier, on envoie une page HTML avec la liste des fichiers du dossier
                File[] files = new File(fichier).listFiles();
                StringBuilder contenu = new StringBuilder();
                String urlPath = recuTab[1];
                contenu.append("<!DOCTYPE html>\n");
                contenu.append("<html lang=\"fr\">\n");
                contenu.append("<head>\n");
                contenu.append("    <meta charset=\"UTF-8\">\n");
                contenu.append("    <title>Index of ").append(urlPath).append("</title>\n");
                contenu.append("    <link rel=\"stylesheet\" href=\"/style.css\">\n");
                contenu.append("</head>\n");
                contenu.append("<body class=\"directory-listing\">\n");
                contenu.append("    <h1>Index of ").append(urlPath).append("</h1>\n");
                contenu.append("    <div class=\"dir-actions\">\n");
                contenu.append("        <a href=\"../\" class=\"back-link\">📁 Dossier Parent</a>\n");
                contenu.append("    </div>\n");
                contenu.append("    <ul class=\"dir-list\">\n");

                if (files != null) {
                    for (File f : files) {
                        String nom = f.getName();
                        if (f.isDirectory()) {
                            // Dossier : on ajoute un "/" visuel et on le met en gras
                            contenu.append("        <li class=\"dir-item folder\"><b><a href=\"").append(nom).append("/\">");
                            contenu.append("📁 ").append(nom).append("/");
                            contenu.append("</a></b></li>\n");
                        } else {
                            // Fichier normal
                            contenu.append("        <li class=\"dir-item file\"><a href=\"").append(nom).append("\">");
                            contenu.append("📄 ").append(nom);
                            contenu.append("</a></li>\n");
                        }
                    }
                }
                contenu.append("    </ul>\n");
                contenu.append("</body>\n</html>\n");

                envoyerHeader(writer, "200 OK", "text/html; charset=UTF-8", contenu.toString().getBytes().length, false, "", System.currentTimeMillis(), false);
                writer.print(contenu);
                log("200 OK: Contenu du dossier " + fichier + " envoyé au client", false);
            } else if (isForbidden) { // Le client tente d'accéder à un fichier en dehors du document root, on envoie une erreur 403
                envoyerHeader(writer, "403 Forbidden", "", 0, false, "", 0, false);
                log("403 Forbidden: Tentative d'accès à un fichier en dehors du document root: " + fichier, true);
            } else if (isModifiedSince) { // Le fichier n'a pas été modifié depuis la dernière fois qu'il a été demandé, on envoie une réponse 304 Not Modified
                envoyerHeader(writer, "304 Not Modified", "", 0, false, eTag, 0, false);
                log("304 Not Modified: " + fichier, false);
            } else if (!fichierValide) { // Le fichier n'est pas valide, on envoie une erreur 404
                envoyerHeader(writer, "404 ERROR", "", 0, false, "", 0, false);
                log("404 ERROR: Fichier non trouvé: " + fichier, true);
            } else { // Le fichier est correct et il est envoyé
                String contentType = Files.probeContentType(Paths.get(fichier));

                // CAS 1 : C'est un fichier HTML, on doit potentiellement exécuter les scripts intégrés
                if (fichier.endsWith(".html")) {
                    envoyerHeader(writer, "200 OK", "text/html; charset=UTF-8", new File(fichier).length(), true, eTag, lastModifiedMs, false);

                    BufferedReader src = new BufferedReader(new FileReader(fichier));
                    StringBuilder contenu = new StringBuilder();
                    String line;
                    while ((line = src.readLine()) != null) {
                        contenu.append(line).append("\n");
                    }
                    src.close();

                    Pattern pattern = Pattern.compile("<code interpreteur=«(.*?)»>(.*?)</code>", Pattern.DOTALL);
                    Matcher matcher = pattern.matcher(contenu.toString());

                    StringBuilder res = new StringBuilder();
                    while (matcher.find()) {
                        String interpreteur = matcher.group(1).trim();
                        String commande = matcher.group(2).trim();
                        String resultat = executer(interpreteur, commande);
                        matcher.appendReplacement(res, Matcher.quoteReplacement(resultat));
                    }
                    matcher.appendTail(res);

                    writer.print(res);
                    writer.flush();

                }
                // CAS 2 : Tout autre type de fichier seront compressé en gzip (CSS, JS, PNG, JPG, PDF...)
                else {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
                         FileInputStream fis = new FileInputStream(fichier)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = fis.read(buffer)) > 0) {
                            gzos.write(buffer, 0, len);
                        }
                        gzos.finish();
                    }
                    byte[] compressedData = baos.toByteArray();

                    envoyerHeader(writer, "200 OK", contentType, compressedData.length, true, eTag, lastModifiedMs, true);

                    DataOutputStream dataWriter = new DataOutputStream(client.getOutputStream());
                    dataWriter.write(compressedData);
                    dataWriter.flush();
                }

                log("200 OK: " + fichier + " (" + contentType + ")", false);
            }

            // Le flush sert pour s'assurer que le message est envoyé immédiatement au client
            // sinon il pourrait rester bloqué dans le buffer du PrintWriter et ne pas être envoyé tant que le buffer n'est pas plein ou que le PrintWriter n'est pas fermé
            writer.flush();
            client.close();
            log("Connexion fermée avec " + client.getRemoteSocketAddress(), false);
        }
    }

    /**
     * Génère et envoie l'en-tête de réponse HTTP/1.1 au client.
     *
     * @param writer Le flux d'écriture textuel vers le client.
     * @param status Le code de statut HTTP (ex. "200 OK", "404 ERROR").
     * @param contentType Le type MIME du contenu (ex. "text/html; charset=UTF-8").
     * @param contentLength La taille du contenu en octets.
     * @param isModifiedSince true si l'on gère le protocole de modification pour envoyer un ETag, false sinon.
     * @param etag La valeur ETag générée pour la ressource.
     * @param lastModified Le timestamp en millisecondes de la dernière modification de la ressource.
     * @param isGzipped true si il s'agit d'un fichier qui peuvent être dans un gzip, false sinon.
     */
    private void envoyerHeader(PrintWriter writer, String status, String contentType, long contentLength, boolean isModifiedSince, String etag, long lastModified, boolean isGzipped) {
        // 1. On récupère la date de modification du fichier et on la formate au standard HTTP (RFC_1123)
        // Utilisation d'internet pour trouver le formatage de la date au standard HTTP (RFC_1123) à partir d'un timestamp en millisecondes
        String lastModifiedStr = RFC_1123_DATE_TIME.withZone(ZoneId.of("GMT")).withLocale(Locale.US).format(Instant.ofEpochMilli(lastModified));
        String reponseDate = RFC_1123_DATE_TIME.withZone(ZoneId.of("GMT")).withLocale(Locale.US).format(Instant.ofEpochMilli(System.currentTimeMillis()));
        String serverName = "QwackServer/1.0";

        writer.print("HTTP/1.1 " + status + "\r\n");
        writer.print("Date: " + reponseDate + "\r\n");
        writer.print("Server: " + serverName + "\r\n");
        if (status.equals("200 OK")) {
            writer.print("Content-Type: " + contentType + "\r\n");
            if (isGzipped) {
                writer.print("Content-Encoding: gzip\r\n");
            }
            writer.print("cache-control: max-age=0\r\n");
            writer.print("Last-Modified: " + lastModifiedStr + "\r\n");
            if (isModifiedSince) {
                writer.print("ETag: " + etag + "\r\n");
            } else {
                writer.print("Content-Length: " + contentLength + "\r\n");
            }
        }
        writer.print("Connection: close\r\n\r\n");
        writer.flush();
    }

    /**
     * Enregistre un message d'activité ou d'erreur à la fois sur la console et dans le fichier de log approprié.
     *
     * @param message Le message à journaliser.
     * @param isError true si le message concerne une erreur (à écrire dans errorlog),
     *                false s'il s'agit d'un accès normal (à écrire dans acceslog).
     */
    private void log(String message, boolean isError) {
        String timestamp = java.time.LocalDateTime.now().toString();

        System.out.println("[" + timestamp + "] " + getPort() + " : " + message);
        message = "[" + timestamp + "] " + serverSocket.getLocalPort() + " - " + client.getRemoteSocketAddress() + " : " + message;

        String fileLog;
        if (isError) fileLog = errorlog;
        else fileLog = acceslog;

        try {
            // Si le dossier du log n'existe pas, on le crée (pour éviter les erreurs de type "No such file or directory")
            File logFile = new File(fileLog);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            // Le paramètre 'true' indique qu'on veut écrire à la suite
            FileWriter file = new FileWriter(logFile, true);
            file.write(message + "\n");
            file.close();
        } catch (IOException e) {
            System.err.println("Erreur lors de l'ouverture du fichier de log: " + e.getMessage());
        }
    }


    // Recherche sur internet pour trouver une méthode de récupération du status du système (mémoire, espace disque, nombre de processus)
    /**
     * Récupère la mémoire disponible du système en Mo. Tente d'abord de lire le fichier /proc/meminfo (Linux),
     * puis utilise Runtime.getRuntime().freeMemory() comme solution de secours.
     *
     * @return La mémoire disponible en Mo.
     */
    private static long getSystemFreeMemory() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]) / 1024; // convert to Mo
                    }
                }
            }
        } catch (Exception e) {
            // fallback
        }
        return Runtime.getRuntime().freeMemory() / 1024 / 1024;
    }

    /**
     * Récupère le statut du système (mémoire disponible, espace disque et nombre de processus actifs)
     * et génère une page HTML dynamique présentant ces informations.
     *
     * @return Le contenu HTML représentant le statut actuel du serveur.
     */
    private String getSystemStatus() {
        // 1. Mémoire disponible réelle du PC
        long freeMemory = getSystemFreeMemory();

        // 2. Espace disque disponible
        File root = new File("/");
        long freeDisk = root.getFreeSpace() / 1024 / 1024 / 1024; // en Go

        // 3. Nombre de processus
        long processCount = ProcessHandle.allProcesses().count();

        return "<!DOCTYPE html>\n" +
                "<html lang=\"fr\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <title>Statut du Serveur</title>\n" +
                "    <link rel=\"stylesheet\" href=\"/style.css\">\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <h1>📊 Statut du Serveur</h1>\n" +
                "        <p>Voici l'état actuel des ressources du serveur :</p>\n" +
                "\n" +
                "        <div class=\"feature-card\">\n" +
                "            <strong>💾 Mémoire disponible :</strong> " + freeMemory + " Mo\n" +
                "        </div>\n" +
                "\n" +
                "        <div class=\"feature-card\">\n" +
                "            <strong>💽 Espace disque disponible :</strong> " + freeDisk + " Go\n" +
                "        </div>\n" +
                "\n" +
                "        <div class=\"feature-card\">\n" +
                "            <strong>⚙️ Nombre de processus actifs :</strong> " + processCount + "\n" +
                "        </div>\n" +
                "\n" +
                "        <div style=\"margin-top: 20px;\">\n" +
                "            <a href=\"/\" class=\"back-link\">🏠 Retour à l'accueil</a>\n" +
                "        </div>\n" +
                "\n" +
                "        <footer>\n" +
                "            <p>SAÉ S2.03 - Serveur Web en Java</p>\n" +
                "        </footer>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>\n";
    }

    // Recherche sur internet pour trouver une méthode de génération d'ETag à partir du contenu d'un fichier
    /**
     * Calcule une empreinte ETag unique pour un fichier donné à l'aide de l'algorithme SHA-256.
     *
     * @param filePath Le chemin du fichier pour lequel calculer l'ETag.
     * @return L'ETag sous forme de chaîne de caractères encodée en Base64 entourée de guillemets.
     * @throws Exception Si une erreur survient lors du calcul du condensat SHA-256 ou de la lecture du fichier.
     */
    public static String getETag(String filePath) throws Exception {
        Path path = Paths.get(filePath);
        // On utilise SHA-256 pour une empreinte unique du contenu
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        // Lecture efficace du fichier par bloc (évite de saturer la RAM)
        byte[] fileBytes = Files.readAllBytes(path);
        byte[] hash = digest.digest(fileBytes);

        return "\"" + Base64.getEncoder().encodeToString(hash) + "\"";
    }

    /**
     * Retourne le port d'écoute local du serveur formaté avec des codes couleur ANSI (vert).
     *
     * @return Le port d'écoute sous forme de chaîne formatée ANSI.
     */
    private String getPort() {
        return GREEN + serverSocket.getLocalPort() + RESET;
    }
}
