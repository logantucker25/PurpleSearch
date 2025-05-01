package main;

import java.io.IOException;

import api.NativeHttpServer;

public class Main {

    public static void main(String[] args) {
        System.out.println("Starting the application...");

        // 0) Start the minimal HTTP server on port 8080
        try {
            NativeHttpServer server = new NativeHttpServer(8080);
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
            return; // If we can't start the server, exit
        }
    }
}
