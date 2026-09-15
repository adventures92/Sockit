'use strict';

/*
 * Echo server for the :socketio integration suites (jvmTest, androidDeviceTest,
 * iosSimulatorArm64Test).
 *
 * Written for this repository: it implements exactly the handlers the Kotlin tests
 * exercise and nothing else. `SocketTestServer` spawns it with PORT set to an
 * ephemeral port, so several instances run side by side during a build.
 *
 * Environment:
 *   PORT      port to listen on                        (default 3000)
 *   RECOVERY  "1" enables connectionStateRecovery      (socket.io >= 4.6)
 *
 * Argument:
 *   argv[2]   main namespace to serve                  (default "/")
 *
 * Engine.IO defaults are kept deliberately: a short pingInterval races the
 * reconnect and transport-drop tests and buys nothing for an echo server.
 */

const http = require('http');
const { Server } = require('socket.io');

const PORT = Number(process.env.PORT) || 3000;
const MAIN_NAMESPACE = process.argv[2] || '/';

const options = {};
if (process.env.RECOVERY === '1') {
  options.connectionStateRecovery = {
    maxDisconnectionDuration: 2 * 60 * 1000,
    skipMiddlewares: true,
  };
}

const httpServer = http.createServer();
const io = new Server(httpServer, options);

/*
 * Secondary namespace. Tests only need it to accept connections — it backs the
 * multiplexing cases (two namespaces, one engine) and the per-namespace state
 * assertions.
 */
io.of('/foo').on('connection', () => {});

/*
 * Rejecting namespace. The middleware error drives the CONNECT_ERROR path:
 * `message` becomes SocketError.ConnectError.message and `data` its .data.
 */
io.of('/no').use((socket, next) => {
  const error = new Error('auth failed');
  error.data = { a: 'b', c: 3 };
  next(error);
});

io.of(MAIN_NAMESPACE).on('connection', (socket) => {
  // Plain round trip. Echoes every argument in order, binary included, so the
  // mixed binary/scalar ordering assertions run through here too.
  socket.on('echo', (...args) => {
    socket.emit('echoBack', ...args);
  });

  // Server-originated binary attachment.
  socket.on('echoBinary', () => {
    socket.emit('echoBinaryBack', Buffer.from([0x01, 0x02, 0x03, 0x04]));
  });

  // Client asks for an ack: reply with the arguments we were given.
  socket.on('ack', (...args) => {
    const respond = args.pop();
    if (typeof respond === 'function') {
      respond(...args);
    }
  });

  // Server asks for an ack: emit 'ack' with a callback, then report what the
  // client sent back as 'ackBack'. Exercises the client's Ack responder.
  socket.on('callAck', () => {
    socket.emit('ack', (...args) => {
      socket.emit('ackBack', ...args);
    });
  });

  // Acks with the handshake, whose `auth` echoes the client's CONNECT payload.
  socket.on('getHandshake', (respond) => {
    if (typeof respond === 'function') {
      respond(socket.handshake);
    }
  });

  // Note: there is deliberately no 'neverAckedEvent' handler — the ack-timeout
  // test relies on the server never replying.
});

httpServer.listen(PORT, () => {
  // SocketTestServer waits for this exact line before handing the port to tests.
  console.log('Socket.IO server listening on port', PORT);
});
