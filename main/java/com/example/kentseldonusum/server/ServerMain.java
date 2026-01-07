package com.example.kentseldonusum.server;

/**
 * Basit başlatıcı: yerel HTTP sunucusunu başlatır ve varsayılan tarayıcıyı açar.
 */
public class ServerMain {
    public static void main(String[] args) {
        int port = 8080;
        try {
            HttpServerApp.start(port);
            // Tarayıcıyı aç (mümkünse)
            try {
                java.awt.Desktop.getDesktop().browse(new java.net.URI("http://localhost:" + port + "/"));
            } catch (Exception ex) {
                System.out.println("Tarayıcı açılamadı: " + ex.getMessage());
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Sunucu başlatılırken hata: " + e.getMessage());
            System.exit(1);
        }
    }
}

