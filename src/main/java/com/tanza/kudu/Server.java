package com.tanza.kudu;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

/**
 * @author jtanza
 */
@AllArgsConstructor
public class Server {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 1024;
    private static final int SELECTOR_TIME_OUT_MS = 1000;

    private final String host;
    private final int port;
    private final RequestDispatcher requestDispatcher;

    public Server(RequestDispatcher requestDispatcher) {
        this.host = DEFAULT_HOST;
        this.port = DEFAULT_PORT;
        this.requestDispatcher = requestDispatcher;
    }

    public void serve() throws IOException {
        ServerConnection serverConnection = ServerConnection.openConnection(host, port);
        Selector selector = serverConnection.getSelector();

        while (true) {
            selector.select(SELECTOR_TIME_OUT_MS);

            Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                iter.remove();
                processSocketEvent(selector, key);
            }
        }
    }

    private void processSocketEvent(Selector selector, SelectionKey key) {
        if (!key.isValid()) {
            return;
        }

        if (key.isAcceptable()) {
            registerChannel(key, selector);
        } else if (key.isReadable()) {
            readThenWrite(key);
        }
    }

    private void readThenWrite(SelectionKey key) {
        SocketBuffer buffer = new SocketBuffer(key);
        buffer.readFromChannel().ifPresent(read -> {
            Request request = Request.from(read);
            requestDispatcher.getHandlerFor(request).ifPresent(handler -> processAsync(key, request, handler));
        });
    }

    private void processAsync(SelectionKey key, Request request, RequestHandler handler) {
        CompletableFuture.supplyAsync(() -> handler.getAction().apply(request))
            .thenAccept(response -> write(key, response))
            .thenAccept((completable) -> key.cancel());
    }

    private static void write(SelectionKey key, Response response) {
        try {
            SocketChannel channel = (SocketChannel) key.channel();
            channel.write(response.toByteBuffer());
            Utils.closeConnection(key);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void registerChannel(SelectionKey key, Selector selector) {
        try {
            SocketChannel socketChannel = ((ServerSocketChannel) key.channel()).accept();
            if (socketChannel != null) {
                socketChannel.configureBlocking(false);
                socketChannel.register(selector, SelectionKey.OP_READ);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Data
    private static class ServerConnection {
        private final Selector selector;
        private final ServerSocketChannel serverSocket;

        static ServerConnection openConnection(String host, int port) {
            try {
                ServerSocketChannel serverSocket = ServerSocketChannel.open();
                serverSocket.configureBlocking(false);
                serverSocket.socket().bind(new InetSocketAddress(host, port));

                Selector selector = Selector.open();
                serverSocket.register(selector, SelectionKey.OP_ACCEPT);
                return new ServerConnection(selector, serverSocket);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
