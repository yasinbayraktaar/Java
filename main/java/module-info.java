module com.example.kentseldonusum {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires com.google.gson;
    requires jdk.httpserver;
    requires jdk.jsobject;
    requires java.logging;
    requires java.net.http;
    requires java.desktop;

    opens com.example.kentseldonusum to javafx.fxml;
    exports com.example.kentseldonusum;
    exports com.example.kentseldonusum.service;
    exports com.example.kentseldonusum.bridge;
    exports com.example.kentseldonusum.server;
    opens com.example.kentseldonusum.server to com.google.gson;
}
