package org.dsa.iot.dslink.provider.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.CharsetUtil;
import org.dsa.iot.dslink.connection.NetworkClient;
import org.dsa.iot.dslink.provider.WsProvider;
import org.dsa.iot.dslink.util.URLInfo;
import org.dsa.iot.dslink.util.http.WsClient;
import org.dsa.iot.dslink.util.json.EncodingFormat;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.dsa.iot.shared.SharedObjects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * @author Samuel Grenier
 */
public class DefaultWsProvider extends WsProvider {

    private static final Logger LOGGER;

    @Override
    public void connect(WsClient client) {
        if (client == null) {
            throw new NullPointerException("client");
        }
        final URLInfo url = client.getUrl();
        String full = url.protocol + "://" + url.host
                + ":" + url.port + url.path;
        URI uri;
        try {
            uri = new URI(full);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        WebSocketVersion v = WebSocketVersion.V13;
        HttpHeaders h = new DefaultHttpHeaders();
        final WebSocketClientHandshaker wsch = WebSocketClientHandshakerFactory
                .newHandshaker(uri, v, null, true, h, Integer.MAX_VALUE);
        final WebSocketHandler handler = new WebSocketHandler(wsch, client);

        Bootstrap b = new Bootstrap();
        b.group(SharedObjects.getLoop());
        b.channel(NioSocketChannel.class);
        b.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ChannelPipeline p = ch.pipeline();
                if (url.secure) {
                    TrustManagerFactory man = InsecureTrustManagerFactory.INSTANCE;
                    SslContext con = SslContext.newClientContext(man);
                    p.addLast(con.newHandler(ch.alloc()));
                }

                p.addLast(new HttpClientCodec());
                p.addLast(new HttpObjectAggregator(8192));
                p.addLast(new WebSocketClientCompressionHandler());
                p.addLast(handler);
            }
        });

        ChannelFuture fut = b.connect(url.host, url.port);
        fut.syncUninterruptibly();
        handler.handshakeFuture().syncUninterruptibly();
    }

    private static class WebSocketHandler extends SimpleChannelInboundHandler<Object> {

        private final WsClient client;

        private WebSocketClientHandshaker handshake;
        private ChannelPromise handshakeFuture;

        public WebSocketHandler(WebSocketClientHandshaker handshake,
                                WsClient client) {
            this.handshake = handshake;
            this.client = client;
        }

        public ChannelFuture handshakeFuture() {
            return handshakeFuture;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            super.handlerAdded(ctx);
            handshakeFuture = ctx.newPromise();
        }

        @Override
        public void channelActive(final ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);
            handshake.handshake(ctx.channel());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            super.channelInactive(ctx);
            client.onDisconnected();
        }

        @Override
        public void messageReceived(final ChannelHandlerContext ctx,
                                    Object msg) {
            final Channel ch = ctx.channel();
            if (handshake != null && !handshake.isHandshakeComplete()) {
                handshake.finishHandshake(ch, (FullHttpResponse) msg);
                handshake = null;
                if (handshakeFuture != null) {
                    handshakeFuture.setSuccess();
                    handshakeFuture = null;
                }
                client.onConnected(new NetworkClient() {

                    @Override
                    public boolean writable() {
                        return ch.isWritable();
                    }

                    @Override
                    public void write(EncodingFormat format,
                                      JsonObject data) {
                        switch (format) {
                            case MESSAGE_PACK: {
                                byte[] bytes = data.encode(format);
                                ByteBuf buf = Unpooled.wrappedBuffer(bytes);
                                BinaryWebSocketFrame f = new BinaryWebSocketFrame(buf);
                                ch.writeAndFlush(f);
                                break;
                            }
                            case JSON: {
                                byte[] bytes = data.encode(format);
                                ByteBuf buf = Unpooled.wrappedBuffer(bytes);
                                WebSocketFrame frame = new TextWebSocketFrame(buf);
                                ch.writeAndFlush(frame);
                                break;
                            }
                            default: {
                                String err = "Unsupported encoding format: {}";
                                LOGGER.error(err, format);
                            }
                        }

                    }

                    @Override
                    public void close() {
                        ctx.close();
                    }

                    @Override
                    public boolean isConnected() {
                        return ch.isOpen();
                    }
                });
                return;
            }

            if (msg instanceof FullHttpResponse) {
                FullHttpResponse response = (FullHttpResponse) msg;
                throw new IllegalStateException(
                        "Unexpected FullHttpResponse (getStatus=" + response.status() +
                                ", content=" + response.content().toString(CharsetUtil.UTF_8) + ')');
            }

            WebSocketFrame frame = (WebSocketFrame) msg;
            if (frame instanceof TextWebSocketFrame
                    || frame instanceof BinaryWebSocketFrame) {
                byte[] bytes;
                {
                    ByteBuf content = frame.content();
                    if (content.hasArray()) {
                        bytes = content.array();
                    } else {
                        bytes = new byte[content.readableBytes()];
                        content.readBytes(bytes);
                    }
                }
                client.onData(bytes);
            } else if (frame instanceof PingWebSocketFrame) {
                ByteBuf buf = frame.content().retain();
                PongWebSocketFrame pong = new PongWebSocketFrame(buf);
                ctx.channel().writeAndFlush(pong);
            } else if (frame instanceof CloseWebSocketFrame) {
                client.onDisconnected();
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            client.onThrowable(cause);
            if (handshakeFuture != null) {
                handshakeFuture.setFailure(cause);
            }
            ctx.close();
        }
    }

    static {
        LOGGER = LoggerFactory.getLogger(DefaultWsProvider.class);
    }
}
