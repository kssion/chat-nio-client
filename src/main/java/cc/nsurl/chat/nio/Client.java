package cc.nsurl.chat.nio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;

public class Client {
    private Selector selector = null;

    private Map<Integer, TimeInterval> ts = new HashMap<>();

    private final LinkedList<ChangeRequest> pendingChanges = new LinkedList<>();
    private final HashMap<SocketChannel, List<ByteBuffer>> pendingEvent = new HashMap<>();

    private final ByteBuffer readBuffer = ByteBuffer.allocate(1024);

    boolean running = true;

    private Client(String host, int port, int count) {
        try {
            selector = Selector.open();
        } catch (IOException e) {
            e.printStackTrace();
        }

        init(host, port, count);
    }

    private void init(String host, int port, int count) {
        System.out.println("*** host:" + host + ", port:" + port + ", count:" + count);

        InetSocketAddress isa = new InetSocketAddress(host, port);

        for (int i = 0; i < count; i++) {
            try {
                SocketChannel sc = SocketChannel.open(isa);
                sc.configureBlocking(false);
                sc.register(selector, SelectionKey.OP_READ);
                System.out.println(sc.getLocalAddress() + " -> " + (i + 1));

            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }

        new ClientThread().start();

        Set<SelectionKey> keys = selector.keys();
        for (SelectionKey k : keys) {
            ts.put(k.channel().hashCode(), new TimeInterval());
        }

        //创建键盘输入流
        Scanner scan = new Scanner(System.in);

        while (scan.hasNextLine()) {
            //读取键盘输入
            String line = scan.nextLine();

            if (line.equals("ls")) {

                int i = 0;
                for (SelectionKey k : keys) {
                    if (k.isValid()) {
                        SocketChannel socket = (SocketChannel) k.channel();
                        TimeInterval ti = ts.get(socket.hashCode());
                        System.out.print(String.format(" %.1f ", (ti.endTime - ti.startTime) / 1000000.0));

                        if (++i % 10 == 0) {
                            System.out.println();
                        }
                    }
                }
                continue;
            }

            if (line.equals("end")) {
                Set<SelectionKey> all = selector.keys();
                for (SelectionKey k : all) {
                    if (k.isValid()) {
                        SocketChannel sc = (SocketChannel) k.channel();
                        try {
                            sc.shutdownInput();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }

                running = false;
                this.selector.wakeup();
            }

            System.out.println("开始发送");
            for (SelectionKey k : keys) {
                send((SocketChannel)k.channel(), line.getBytes());
            }
            System.out.println("发送完成");
        }
    }

    private void send(SocketChannel socket, byte[] bytes) {
        synchronized (this.pendingChanges) {
            ChangeRequest request = new ChangeRequest(socket, ChangeRequest.OP_CHANGE, SelectionKey.OP_WRITE);
            this.pendingChanges.add(request);

            synchronized (this.pendingEvent) {
                List<ByteBuffer> queue = this.pendingEvent.computeIfAbsent(socket, k -> new LinkedList<>());
                queue.add(ByteBuffer.wrap(bytes));
            }
        }
        this.selector.wakeup();
    }

    private void readable(SelectionKey key) throws IOException {
        //使用NIO读取channel中的数据
        SocketChannel socket = (SocketChannel)key.channel();

        readBuffer.clear();

        int len;

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        while ((len = socket.read(readBuffer)) > 0) {
            bytes.write(readBuffer.array(), 4, len - 4);
            readBuffer.clear();
        }

        if (len == -1) {
            key.cancel();
            if (key.channel() != null) {
                socket.shutdownInput();
            }
            throw new IOException("断开连接 " + socket.getRemoteAddress());
        } else {

            TimeInterval ti = ts.get(socket.hashCode());
            ti.endTime = System.nanoTime();

            // TODO: Hand the data off to our worker thread
            // print new String(bytes.toByteArray());

            System.out.println(bytes.toString());

            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void writable(SelectionKey key) throws IOException {

        SocketChannel socket = (SocketChannel)key.channel();
        TimeInterval ti = ts.get(socket.hashCode());
        ti.startTime = System.nanoTime();

        synchronized (this.pendingEvent) {
            List<ByteBuffer> queue = this.pendingEvent.get(socket);

            while (!queue.isEmpty()) {

                ByteBuffer buffer = queue.get(0);
                socket.write(buffer);
                if (buffer.hasRemaining()) {
                    break;
                }
                queue.remove(0);
            }

            if (queue.isEmpty() && key.isValid()) {
                key.interestOps(SelectionKey.OP_READ);
            }
        }
    }

    //定义读取服务器数据的线程
    private class ClientThread extends Thread {

        public void run() {
            while (running) {
                synchronized (pendingChanges) {
                    for (ChangeRequest change : pendingChanges) {
                        switch (change.type) {
                            case ChangeRequest.OP_CHANGE:
                                SelectionKey key = change.socket.keyFor(selector);
                                key.interestOps(change.ops);
                                break;
                        }
                    }
                    pendingChanges.clear();
                }

                try {
                    if (selector.select() == 0) continue;
                } catch (IOException e) {
                    e.printStackTrace();
                }

                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    try {
                        if (key.isReadable()) {
                            readable(key);
                        } else if (key.isWritable()) {
                            writable(key);
                        }
                    } catch (IOException e) {

                        if (key.channel() instanceof SocketChannel) {
                            SocketChannel socket = (SocketChannel) key.channel();

                            // TODO 用户出现异常 退出
                            try {
                                socket.shutdownOutput();
                            } catch (IOException ignored) {
                            }
                            key.cancel();
                            selector.wakeup();
                        }
                    }
                }
                selector.selectedKeys().clear();
            }
        }
    }

    private class TimeInterval {
        private long startTime;
        private long endTime;
    }

    class ChangeRequest {
        static final int OP_CHANGE = 1;

        SocketChannel socket;
        int type;
        int ops;

        ChangeRequest(SocketChannel socket, int type, int ops) {
            this.socket = socket;
            this.type = type;
            this.ops = ops;
        }
    }

    public static void main(String[] args) {
        String host = "0.0.0.0";
        int port = 9090;
        int count = 100;

        for (int i = 0; i < args.length; i++) {
            String str = args[i];

            switch (str) {
                case "-h":  // 主机
                    host = args[++i];
                    break;
                case "-p":  // 端口
                    port = Integer.parseInt(args[++i]);
                    break;
                case "-c":  // 总数
                    count = Integer.parseInt(args[++i]);
                    break;
                default:  //
                    System.err.println("error params.\n-h host default 0.0.0.0\n-p port default 9090\n-c count default 100");
                    System.out.println("default: host:" + host + ", port:" + port + ", count:" + count);
                    break;
            }
        }

        new Client(host, port, count);
    }
}