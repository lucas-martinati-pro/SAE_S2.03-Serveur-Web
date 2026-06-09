import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;

/**
 * Classe principale du serveur web responsable du chargement de la configuration
 * XML et de l'initialisation des différents sites (instances de Site).
 */
public class ServerMain {
    /**
     * Extrait la valeur textuelle d'une balise XML spécifique contenue dans un élément.
     * Si la balise est absente ou vide, renvoie la valeur par défaut spécifiée.
     *
     * @param tag Le nom de la balise XML à rechercher.
     * @param element L'élément XML parent contenant la balise.
     * @param defaultValue La valeur par défaut à renvoyer si la balise n'est pas trouvée.
     * @return La valeur textuelle de la balise après suppression des espaces superflus, ou defaultValue.
     */
    private String getTagValue(String tag, Element element, String defaultValue) {
        NodeList nl = element.getElementsByTagName(tag);
        if (nl.getLength() > 0) {
            return nl.item(0).getTextContent().trim();
        }
        return defaultValue;
    }

    /**
     * Lit le fichier de configuration XML spécifié et démarre une instance de serveur
     * Site pour chaque bloc de configuration <site> trouvé.
     *
     * @param nomFichier Le chemin du fichier de configuration XML.
     * @throws Exception Si une erreur survient lors de la lecture ou de l'analyse du fichier XML.
     */
    public void configureClients(String nomFichier) throws Exception {
        // 1. Initialisation du parseur XML
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new File(nomFichier));

        // Normalisation pour éviter les espaces inutiles entre les nœuds
        doc.getDocumentElement().normalize();

        // 2. Récupération de tous les blocs <site>
        NodeList siteList = doc.getElementsByTagName("site");

        for (int i = 0; i < siteList.getLength(); i++) {
            try {
                Node node = siteList.item(i);

                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;

                    // 3. Extraction des valeurs via les noms de balises
                    int port = Integer.parseInt(getTagValue("port", element, "8080"));
                    String root = getTagValue("DocumentRoot", element, "/var/www2");
                    String index = getTagValue("DefaultIndex", element, "index.html");
                    String access = getTagValue("Acceslog", element, "/var/log/myweb/access.log");
                    String error = getTagValue("Errorlog", element, "/var/log/myweb/error.log");

                    // 4. Ajout du site configuré
                    new Site(port, root, index, access, error).startServer();
                }
            } catch (Exception e) {
                System.err.println("Erreur lors de la configuration d'un site: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) throws IOException {
        ServerMain serverMain = new ServerMain();
        // L'ajout du pid dans le fichier

        // Source - https://stackoverflow.com/a/35885
        // Posted by Wouter Coekaerts, modified by community. See post 'Timeline' for change history
        // Retrieved 2026-06-04, License - CC BY-SA 4.0
        long pid = ProcessHandle.current().pid();

        System.out.println("PID: " + pid);

        // Si le fichier n'existe pas, on le crée (pour éviter les erreurs de type "No such file or directory")
        File parentDir = new File("run/myweb.pid").getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        BufferedWriter myWebPid = new BufferedWriter(new FileWriter("run/myweb.pid"));

        myWebPid.write(String.valueOf(pid));
        myWebPid.close();

        // Le serveur
        try {
            // Lecture des fichiers dans conf.d
            File dossier = new File("conf.d");
            if (dossier.exists() && dossier.isDirectory()) {
                File[] files = dossier.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().endsWith(".conf")) {
                            serverMain.configureClients(f.getPath());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Erreur lors de la configuration des clients: " + e.getMessage());
        }
    }
}
