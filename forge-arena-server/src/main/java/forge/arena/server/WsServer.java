package forge.arena.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

/** Minimal Phase 0 WebSocket transport. Only immutable JSON strings cross threads. */
public final class WsServer implements AutoCloseable {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String HELLO = GSON.toJson(new Hello("hello", 1, "0.8-dev", 0));
    private static final AttributeKey<Boolean> HANDSHAKEN = AttributeKey.valueOf(WsServer.class, "handshaken");

    private final String host;
    private final int port;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final AtomicReference<String> state = new AtomicReference<>();
    private Channel serverChannel;

    public WsServer(final String host, final int port) {
        this.host = host;
        this.port = port;
    }

    /** Bind on the lifecycle owner thread, never on a Netty I/O thread. */
    public void start() throws InterruptedException {
        checkLifecycleThread();
        if (serverChannel != null) {
            throw new IllegalStateException("WebSocket server already started");
        }
        final ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel channel) {
                        channels.add(channel);
                        channel.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(65536),
                                new WebSocketServerProtocolHandler("/ws"), new MessageHandler());
                    }
                });
        serverChannel = bootstrap.bind(host, port).sync().channel();
        channels.add(serverChannel);
    }

    public int getPort() {
        if (serverChannel == null) {
            throw new IllegalStateException("WebSocket server has not started");
        }
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /** Safe on any thread: cache the snapshot and enqueue writes without waiting for sockets. */
    public void broadcastState(final String json) {
        state.set(json);
        channels.writeAndFlush(new TextWebSocketFrame(json),
                channel -> Boolean.TRUE.equals(channel.attr(HANDSHAKEN).get()));
    }

    /** Stop and await both groups on the lifecycle owner thread. */
    @Override
    public void close() {
        checkLifecycleThread();
        channels.close().syncUninterruptibly();
        final Future<?> bossStopped = bossGroup.shutdownGracefully();
        final Future<?> workerStopped = workerGroup.shutdownGracefully();
        bossStopped.syncUninterruptibly();
        workerStopped.syncUninterruptibly();
    }

    private void checkLifecycleThread() {
        for (final EventExecutor executor : bossGroup) {
            if (executor.inEventLoop()) {
                throw new IllegalStateException("WebSocket lifecycle must run outside Netty I/O threads");
            }
        }
        for (final EventExecutor executor : workerGroup) {
            if (executor.inEventLoop()) {
                throw new IllegalStateException("WebSocket lifecycle must run outside Netty I/O threads");
            }
        }
    }

    private final class MessageHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object event) throws Exception {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                ctx.writeAndFlush(new TextWebSocketFrame(HELLO));
                // Publish eligibility only after hello is queued. Register before reading the
                // cache so a broadcast racing the handshake cannot be missed.
                ctx.channel().attr(HANDSHAKEN).set(true);
                final String cached = state.get();
                if (cached != null) {
                    ctx.writeAndFlush(new TextWebSocketFrame(cached));
                }
            }
            super.userEventTriggered(ctx, event);
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final TextWebSocketFrame frame) {
            System.out.println("[ws] in: " + frame.text());
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
            System.err.println("[ws] " + cause);
            ctx.close();
        }
    }

    private record Hello(String type, int protocolVersion, String serverVersion, int seat) { }
}
