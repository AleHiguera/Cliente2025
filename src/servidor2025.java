import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class servidor2025 {

    private static final int PUERTO = 8080;
    // Archivos de persistencia
    private static final String ARCHIVO_USUARIOS = "cuentas.txt";
    private static final String ARCHIVO_MENSAJES = "mensajes.txt";
    private static final String ARCHIVO_BLOQUEADOS = "bloqueados.txt";
    private static final String ARCHIVO_SOLICITUDES_PENDIENTES = "solicitudes_pendientes.txt";
    // NUEVO: Carpeta para archivos pendientes de descarga en el servidor
    private static final String CARPETA_DESCARGAS_PENDIENTES_SERVIDOR = "descargas_pendientes_servidor";


    // Mapa para gestionar clientes conectados: (nombreUsuario -> Handler)
    // ConcurrentHashMap es seguro para concurrencia
    private static ConcurrentHashMap<String, Handler> clientesConectados = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        // Asegurarse de que los archivos y carpetas necesarios existan al iniciar el servidor
        try {
            new File(ARCHIVO_USUARIOS).createNewFile();
            new File(ARCHIVO_MENSAJES).createNewFile();
            new File(ARCHIVO_BLOQUEADOS).createNewFile();
            new File(ARCHIVO_SOLICITUDES_PENDIENTES).createNewFile();

            // NUEVO: Crear la carpeta principal de descargas pendientes del servidor si no existe
            Path descargasPendientesPath = Paths.get(CARPETA_DESCARGAS_PENDIENTES_SERVIDOR);
            if (!Files.exists(descargasPendientesPath)) {
                Files.createDirectories(descargasPendientesPath);
            }

            System.out.println("Archivos y directorios de persistencia verificados/creados.");
        } catch (IOException e) {
            System.err.println("Error al crear archivos/directorios de persistencia: " + e.getMessage());
            return; // Salir si no se pueden crear
        }

        System.out.println("Servidor iniciado en el puerto " + PUERTO);
        try (ServerSocket serverSocket = new ServerSocket(PUERTO)) {
            while (true) {
                Socket clienteSocket = serverSocket.accept();
                System.out.println("Nuevo cliente conectado: " + clienteSocket.getInetAddress().getHostAddress());
                Handler handler = new Handler(clienteSocket);
                new Thread(handler).start(); // Inicia un nuevo hilo para cada cliente
            }
        } catch (IOException e) {
            System.err.println("Error del servidor: " + e.getMessage());
        }
    }

    // Clase interna para manejar cada cliente en un hilo separado
    private static class Handler implements Runnable {
        private Socket clienteSocket;
        private PrintWriter escritor;
        private BufferedReader lector;
        private String usuarioActual = null; // Nombre de usuario después de la firma

        // NUEVAS VARIABLES para manejar la transferencia de archivos de cliente a cliente
        private String archivoDestinoTransferencia = null; // El usuario al que se le envía un archivo
        private String nombreArchivoEnTransito = null;      // Nombre del archivo que se está recibiendo del cliente
        private BufferedWriter archivoEnEscrituraTemporal = null; // Para guardar en carpeta del servidor si el destino está offline


        public Handler(Socket socket) {
            this.clienteSocket = socket;
        }

        @Override
        public void run() {
            try {
                escritor = new PrintWriter(clienteSocket.getOutputStream(), true);
                lector = new BufferedReader(new InputStreamReader(clienteSocket.getInputStream()));

                escritor.println("Bienvenido. ¿Quieres registrarte (R) o firmar (F)? Escribe salir para terminar.");

                String lineaCliente;
                while ((lineaCliente = lector.readLine()) != null) {
                    if (lineaCliente.equalsIgnoreCase("salir")) {
                        break;
                    }

                    if (usuarioActual == null) { // No ha iniciado sesión
                        if (lineaCliente.equalsIgnoreCase("R")) {
                            registrarUsuario();
                        } else if (lineaCliente.equalsIgnoreCase("F")) {
                            firmarUsuario();
                        } else {
                            escritor.println("Opción no válida. Escribe R para registrarte o F para firmar.");
                        }
                    } else { // Sesión iniciada
                        procesarComando(lineaCliente);
                    }
                }
            } catch (IOException e) {
                if (usuarioActual != null) {
                    System.err.println("Error de E/S para " + usuarioActual + ": " + e.getMessage());
                } else {
                    System.err.println("Error de E/S para un cliente no identificado: " + e.getMessage());
                }
            } finally {
                cerrarSesion();
            }
        }

        private void registrarUsuario() throws IOException {
            String nuevoUsuario;
            do {
                escritor.println("Escribe tu nombre de usuario:");
                nuevoUsuario = lector.readLine().toLowerCase(); // Convertir a minúsculas
                if (nuevoUsuario == null || nuevoUsuario.equalsIgnoreCase("salir")) {
                    escritor.println("Registro cancelado.");
                    return;
                }
                if (existeUsuario(nuevoUsuario)) {
                    escritor.println("El usuario '" + nuevoUsuario + "' ya existe. Por favor, elige otro.");
                }
            } while (existeUsuario(nuevoUsuario));

            String pin;
            do {
                escritor.println("Escribe tu PIN de 4 digitos:");
                pin = lector.readLine();
                if (pin == null || pin.equalsIgnoreCase("salir")) {
                    escritor.println("Registro cancelado.");
                    return;
                }
                if (!pin.matches("\\d{4}")) {
                    escritor.println("PIN inválido. Debe ser de 4 dígitos numéricos.");
                }
            } while (!pin.matches("\\d{4}"));

            guardarUsuario(nuevoUsuario, pin);
            usuarioActual = nuevoUsuario;
            clientesConectados.put(usuarioActual, this); // Añadir a clientes conectados
            escritor.println("Registro exitoso. Ahora tu sesión está activa.");
            System.out.println("Usuario " + usuarioActual + " registrado y conectado.");
            mostrarOpciones();
            procesarSolicitudesPendientes(usuarioActual); // Procesar solicitudes pendientes tras el registro
            escritor.println("LISTO PARA COMANDO");
        }

        private void firmarUsuario() throws IOException {
            String usuarioIntento;
            String pinIntento;
            int intentos = 0;
            final int MAX_INTENTOS = 3;

            while (intentos < MAX_INTENTOS) {
                escritor.println("Escribe tu nombre de usuario:");
                usuarioIntento = lector.readLine().toLowerCase(); // Convertir a minúsculas
                if (usuarioIntento == null || usuarioIntento.equalsIgnoreCase("salir")) {
                    escritor.println("Firma cancelada.");
                    return;
                }

                escritor.println("Escribe tu PIN de 4 digitos para firmar:");
                pinIntento = lector.readLine();
                if (pinIntento == null || pinIntento.equalsIgnoreCase("salir")) {
                    escritor.println("Firma cancelada.");
                    return;
                }

                if (validarCredenciales(usuarioIntento, pinIntento)) {
                    if (clientesConectados.containsKey(usuarioIntento)) {
                        escritor.println("El usuario '" + usuarioIntento + "' ya está conectado. Por favor, usa otro usuario o desconecta el existente.");
                        intentos++;
                        continue;
                    }
                    usuarioActual = usuarioIntento;
                    clientesConectados.put(usuarioActual, this); // Añadir a clientes conectados
                    escritor.println("Firma exitosa. ¡Bienvenido " + usuarioActual + "!");
                    System.out.println("Usuario " + usuarioActual + " ha iniciado sesión.");
                    mostrarOpciones();
                    procesarSolicitudesPendientes(usuarioActual); // Procesar solicitudes pendientes tras el login
                    escritor.println("LISTO PARA COMANDO");
                    return;
                } else {
                    intentos++;
                    if (intentos < MAX_INTENTOS) {
                        escritor.println("Credenciales incorrectas. Intenta de nuevo. Te quedan " + (MAX_INTENTOS - intentos) + " intentos.");
                    } else {
                        escritor.println("Demasiados intentos fallidos. Conexión cerrada.");
                        return; // Esto causará que el finally cierre la conexión
                    }
                }
            }
        }

        private void procesarComando(String comando) throws IOException {

            // --- Lógica de Manejo de Archivos en Tránsito (de cliente a cliente, via servidor) ---
            if (nombreArchivoEnTransito != null) { // Si hay un archivo en proceso de ser recibido por el servidor
                if (comando.startsWith("CONTENIDO_LINEA_ARCHIVO_DE_CLIENTE:")) {
                    String lineaContenido = comando.substring("CONTENIDO_LINEA_ARCHIVO_DE_CLIENTE:".length() + archivoDestinoTransferencia.length() + 1); // +1 por el segundo ':'

                    Handler destinoHandler = clientesConectados.get(archivoDestinoTransferencia);
                    if (destinoHandler != null) { // Destino está ONLINE: Retransmitir directamente
                        destinoHandler.escritor.println("CONTENIDO_LINEA_DE_USUARIO:" + usuarioActual + ":" + lineaContenido);
                    } else if (archivoEnEscrituraTemporal != null) { // Destino está OFFLINE: Escribir en archivo temporal del servidor
                        archivoEnEscrituraTemporal.write(lineaContenido);
                        archivoEnEscrituraTemporal.newLine();
                    }
                    return; // Consumido por el flujo de transferencia
                } else if (comando.startsWith("TRANSFERENCIA_COMPLETA_DE_CLIENTE:")) {
                    Handler destinoHandler = clientesConectados.get(archivoDestinoTransferencia);
                    if (destinoHandler != null) { // Destino está ONLINE
                        destinoHandler.escritor.println("TRANSFERENCIA_COMPLETA_DE_USUARIO:" + usuarioActual);
                        System.out.println("Servidor: " + usuarioActual + " completó transferencia a " + archivoDestinoTransferencia + " (en línea).");
                    } else if (archivoEnEscrituraTemporal != null) { // Destino está OFFLINE
                        archivoEnEscrituraTemporal.close(); // Cerrar el archivo temporal
                        System.out.println("Servidor: " + usuarioActual + " completó transferencia de '" + nombreArchivoEnTransito + "' a " + archivoDestinoTransferencia + " (offline, guardado en servidor).");
                        // Registrar la notificación para el usuario offline
                        registrarSolicitudPendiente("ARCHIVO_PENDIENTE_DESCARGA", archivoDestinoTransferencia, usuarioActual, nombreArchivoEnTransito);
                    }
                    // Restablecer variables de estado de transferencia del servidor
                    nombreArchivoEnTransito = null;
                    archivoDestinoTransferencia = null;
                    archivoEnEscrituraTemporal = null;
                    return; // Consumido por el flujo de transferencia
                } else if (comando.startsWith("ERROR_TRANSFERENCIA_DE_CLIENTE:")) {
                    String errorMsg = comando.substring("ERROR_TRANSFERENCIA_DE_CLIENTE:".length() + archivoDestinoTransferencia.length() + 1);
                    Handler destinoHandler = clientesConectados.get(archivoDestinoTransferencia);
                    if (destinoHandler != null) {
                        destinoHandler.escritor.println("ERROR_TRANSFERENCIA_DE_USUARIO:" + errorMsg);
                    } else if (archivoEnEscrituraTemporal != null) {
                        archivoEnEscrituraTemporal.close(); // Cerrar el archivo temporal
                        // Eliminar el archivo parcial si hubo un error
                        Path archivoParcial = Paths.get(CARPETA_DESCARGAS_PENDIENTES_SERVIDOR, archivoDestinoTransferencia, nombreArchivoEnTransito);
                        try { Files.deleteIfExists(archivoParcial); } catch (IOException e) { System.err.println("Error al eliminar archivo parcial: " + e.getMessage()); }
                    }
                    System.err.println("Servidor: Error de transferencia de " + usuarioActual + " a " + archivoDestinoTransferencia + ": " + errorMsg);
                    nombreArchivoEnTransito = null;
                    archivoDestinoTransferencia = null;
                    archivoEnEscrituraTemporal = null;
                    return; // Consumido por el flujo de transferencia
                }
            }

            // --- Comandos Generales y Nuevos Comandos ---
            if (comando.equalsIgnoreCase("cerrar")) {
                escritor.println("Cerrando sesión. ¡Hasta pronto!");
                return; // Esto causará que el finally cierre la sesión
            } else if (comando.equalsIgnoreCase("usuarios")) {
                mostrarUsuariosConectados();
            } else if (comando.equalsIgnoreCase("mensaje")) {
                enviarMensaje();
            } else if (comando.equalsIgnoreCase("leer")) {
                leerMensajes();
            } else if (comando.equalsIgnoreCase("eliminar")) {
                eliminarMensajes();
            } else if (comando.equalsIgnoreCase("bcuenta")) {
                borrarCuenta();
            } else if (comando.equalsIgnoreCase("bloquear")) {
                bloquearUsuario();
            } else if (comando.equalsIgnoreCase("desbloquear")) {
                desbloquearUsuario();
            } else if (comando.startsWith("crear ")) {
                escritor.println("CREAR_ARCHIVO_LOCAL:" + comando.substring(6).trim());
            } else if (comando.startsWith("ver ")) {
                verArchivosOtroUsuario(comando.substring(4).trim().toLowerCase());
            } else if (comando.startsWith("pedir ")) {
                String[] partes = comando.substring(6).trim().split(" ", 2);
                if (partes.length == 2) {
                    pedirArchivoOtroUsuario(partes[0].toLowerCase(), partes[1]);
                } else {
                    escritor.println("Formato incorrecto. Usa 'pedir <usuario> <archivo_a_pedir>'.");
                }
            }
            // NUEVO: Comando del cliente para descargar un archivo que el servidor ha guardado para él
            else if (comando.startsWith("DESCARGAR_ARCHIVO_DE_SERVIDOR:")) {
                String nombreArchivo = comando.substring("DESCARGAR_ARCHIVO_DE_SERVIDOR:".length()).trim();
                enviarArchivoPendienteAlCliente(usuarioActual, nombreArchivo);
            }
            // Manejo de la respuesta de lista de archivos del otro cliente
            else if (comando.startsWith("RESPUESTA_ARCHIVOS:")) {
                String[] partes = comando.split(":", 3);
                if (partes.length == 3) {
                    String solicitante = partes[1].toLowerCase();
                    String listaArchivos = partes[2];
                    Handler solicitanteHandler = clientesConectados.get(solicitante);

                    if (solicitanteHandler != null) {
                        // El solicitante (A) está ONLINE: Retransmisión directa
                        solicitanteHandler.escritor.println("ARCHIVOS_DE:" + usuarioActual + ":" + listaArchivos);
                        System.out.println("Lista de archivos de " + usuarioActual + " enviada a " + solicitante + " (en línea).");
                    } else {
                        // El solicitante (A) está OFFLINE: Registrar la lista como pendiente de entrega
                        // TIPO:LISTA_RECIBIDA | DESTINO:solicitante | ORIGEN:usuarioActual | CONTENIDO:listaArchivos
                        registrarSolicitudPendiente("LISTA_RECIBIDA", solicitante, usuarioActual, listaArchivos);
                        System.out.println("Solicitante " + solicitante + " offline. Lista de archivos de " + usuarioActual + " guardada para su entrega posterior.");
                    }
                }
            }
            // NUEVO: El cliente inicia la transferencia de un archivo hacia otro cliente.
            // Esto es el inicio del flujo donde el SERVIDOR puede decidir guardar o retransmitir.
            else if (comando.startsWith("INICIANDO_TRANSFERENCIA_ARCHIVO_DE_CLIENTE:")) {
                String[] partes = comando.split(":", 3);
                if (partes.length == 3) {
                    String destino = partes[1].toLowerCase();
                    String nombreArchivo = partes[2];

                    // Configurar variables de estado del servidor para la transferencia
                    archivoDestinoTransferencia = destino;
                    nombreArchivoEnTransito = nombreArchivo;

                    Handler destinoHandler = clientesConectados.get(destino);
                    if (destinoHandler != null) { // El destinatario está ONLINE: Iniciar retransmisión
                        destinoHandler.escritor.println("INICIANDO_TRANSFERENCIA_DE_USUARIO:" + usuarioActual + ":" + nombreArchivo);
                        System.out.println("Servidor: " + usuarioActual + " inició transferencia de '" + nombreArchivo + "' a " + destino + " (en línea).");
                    } else { // El destinatario está OFFLINE: Iniciar guardado en la carpeta del servidor
                        System.out.println("Servidor: " + destino + " está offline. Guardando '" + nombreArchivo + "' de " + usuarioActual + " en el servidor.");
                        try {
                            // Asegurar que la subcarpeta del usuario destino exista en el servidor
                            Path userDownloadsPath = Paths.get(CARPETA_DESCARGAS_PENDIENTES_SERVIDOR, destino);
                            Files.createDirectories(userDownloadsPath); // Crea la carpeta si no existe

                            File archivoGuardar = new File(userDownloadsPath.toFile(), nombreArchivo);
                            archivoEnEscrituraTemporal = new BufferedWriter(new FileWriter(archivoGuardar));
                        } catch (IOException e) {
                            System.err.println("Error al preparar archivo temporal para " + destino + ": " + e.getMessage());
                            escritor.println("ERROR: No se pudo preparar el archivo temporal en el servidor para " + destino + ".");
                            // Reiniciar flags de transferencia del servidor en caso de error
                            nombreArchivoEnTransito = null;
                            archivoDestinoTransferencia = null;
                            archivoEnEscrituraTemporal = null;
                            return;
                        }
                    }
                }
            }
            // No necesitamos manejar CONTENIDO_LINEA_ARCHIVO_DE_CLIENTE, etc., aquí con else if,
            // porque se manejan en el primer bloque grande si nombreArchivoEnTransito no es null.

            else {
                escritor.println("Comando no reconocido o no válido en este contexto.");
            }
            // Mostrar opciones solo si no es un comando interno o de transferencia de archivo de cliente a cliente
            if (!comando.startsWith("crear ") && !comando.startsWith("RESPUESTA_ARCHIVOS:") &&
                    !comando.startsWith("INICIANDO_TRANSFERENCIA_ARCHIVO_DE_CLIENTE:") &&
                    !comando.startsWith("CONTENIDO_LINEA_ARCHIVO_DE_CLIENTE:") && // Estos se manejan al inicio
                    !comando.startsWith("TRANSFERENCIA_COMPLETA_DE_CLIENTE:") &&  // Estos se manejan al inicio
                    !comando.startsWith("ERROR_TRANSFERENCIA_DE_CLIENTE:") &&     // Estos se manejan al inicio
                    !comando.startsWith("DESCARGAR_ARCHIVO_DE_SERVIDOR:")) { // Y este es nuevo
                mostrarOpciones();
                escritor.println("LISTO PARA COMANDO");
            }
        }

        private void mostrarOpciones() {
            escritor.println("Opciones:");
            escritor.println(" - 'cerrar' para cerrar sesión");
            // escritor.println(" - 'jugar' para comenzar el juego"); // Ejemplo, si tienes un juego
            escritor.println(" - 'usuarios' para ver la lista de usuarios");
            escritor.println(" - 'mensaje' para dejar un mensaje");
            escritor.println(" - 'leer' para ver tus mensajes");
            escritor.println(" - 'eliminar' para borrar mensajes");
            escritor.println(" - 'bcuenta' para borrar tu usuario.");
            escritor.println(" - 'bloquear' para bloquear un usuario");
            escritor.println(" - 'desbloquear' para desbloquear un usuario.");
            escritor.println(" - 'crear <nombre>' para crear un archivo en tu carpeta 'mis_archivos'");
            escritor.println(" - 'ver <usuario>' para ver los archivos de otro usuario");
            escritor.println(" - 'pedir <usuario> <archivo>' para solicitar un archivo a otro usuario");
            escritor.println(" - 'descargar <nombre_archivo>' para descargar un archivo pendiente del servidor."); // NUEVO
        }

        private void mostrarUsuariosConectados() {
            if (clientesConectados.isEmpty() || (clientesConectados.size() == 1 && clientesConectados.containsKey(usuarioActual))) {
                escritor.println("No hay otros usuarios conectados en este momento.");
                return;
            }
            escritor.println("Usuarios conectados:");
            for (String user : clientesConectados.keySet()) {
                if (!user.equalsIgnoreCase(usuarioActual)) { // No mostrarse a sí mismo
                    escritor.println(" - " + user);
                }
            }
        }

        private void enviarMensaje() throws IOException {
            escritor.println("Escribe el nombre del usuario al que quieres enviar el mensaje:");
            String destino = lector.readLine().toLowerCase();
            if (destino == null || destino.equalsIgnoreCase("salir")) {
                escritor.println("Envío de mensaje cancelado.");
                return;
            }
            if (destino.equalsIgnoreCase(usuarioActual)) {
                escritor.println("No puedes enviarte mensajes a ti mismo.");
                return;
            }
            if (!existeUsuario(destino)) {
                escritor.println("El usuario '" + destino + "' no existe.");
                return;
            }
            if (estaBloqueado(destino, usuarioActual)) { // Si DESTINO bloquea a ORIGEN (usuarioActual)
                escritor.println("No puedes enviar mensajes a " + destino + ". Te ha bloqueado.");
                return;
            }

            escritor.println("Escribe el mensaje:");
            String mensaje = lector.readLine();
            if (mensaje == null || mensaje.equalsIgnoreCase("salir")) {
                escritor.println("Envío de mensaje cancelado.");
                return;
            }

            Handler destinoHandler = clientesConectados.get(destino);
            if (destinoHandler != null) { // El destinatario está conectado
                destinoHandler.escritor.println("MENSAJE_NUEVO_DIRECTO: De " + usuarioActual + ": " + mensaje);
                escritor.println("Mensaje enviado a " + destino + ".");
            } else { // El destinatario está desconectado
                guardarMensajeOffline(usuarioActual, destino, mensaje); // Guarda el mensaje en mensajes.txt
                registrarSolicitudPendiente("MENSAJE", destino, usuarioActual, mensaje); // Registra la notificación en solicitudes_pendientes.txt
                escritor.println("Usuario " + destino + " está offline. El mensaje se le entregará cuando se conecte.");
            }
        }

        private void leerMensajes() {
            List<String> misMensajes = obtenerMensajesParaUsuario(usuarioActual);
            if (misMensajes.isEmpty()) {
                escritor.println("No tienes mensajes nuevos.");
            } else {
                escritor.println("Tus mensajes:");
                for (String msg : misMensajes) {
                    escritor.println(msg);
                }
            }
        }

        private void eliminarMensajes() throws IOException {
            List<String> mensajesActuales = new ArrayList<>();
            List<String> mensajesRestantes = new ArrayList<>();
            boolean encontrados = false;

            try (BufferedReader br = new BufferedReader(new FileReader(ARCHIVO_MENSAJES))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    mensajesActuales.add(linea);
                }
            } catch (IOException e) {
                System.err.println("Error al leer archivo de mensajes para eliminar: " + e.getMessage());
                escritor.println("Error al leer mensajes.");
                return;
            }

            if (mensajesActuales.isEmpty()) {
                escritor.println("No tienes mensajes para eliminar.");
                return;
            }

            escritor.println("¿Quieres eliminar todos tus mensajes (T) o solo los de un usuario específico (U)?");
            String opcion = lector.readLine();

            if (opcion != null && opcion.equalsIgnoreCase("T")) {
                for (String msg : mensajesActuales) {
                    if (!msg.startsWith(usuarioActual + ":")) { // Conservar mensajes que no son míos
                        mensajesRestantes.add(msg);
                    } else {
                        encontrados = true;
                    }
                }
                if (encontrados) {
                    escritor.println("Todos tus mensajes han sido eliminados.");
                } else {
                    escritor.println("No tienes mensajes para eliminar.");
                }
            } else if (opcion != null && opcion.equalsIgnoreCase("U")) {
                escritor.println("Escribe el nombre del usuario de quien quieres eliminar mensajes:");
                String remitente = lector.readLine().toLowerCase();
                for (String msg : mensajesActuales) {
                    // Si el mensaje es para el usuario actual y viene del remitente especificado
                    if (msg.startsWith(usuarioActual + ":De " + remitente + ":")) {
                        encontrados = true;
                    } else {
                        mensajesRestantes.add(msg);
                    }
                }
                if (encontrados) {
                    escritor.println("Mensajes de '" + remitente + "' eliminados.");
                } else {
                    escritor.println("No tienes mensajes de '" + remitente + "' para eliminar.");
                }
            } else {
                escritor.println("Opción no válida. Ningún mensaje eliminado.");
            }

            try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_MENSAJES, false))) { // false para sobreescribir
                for (String msg : mensajesRestantes) {
                    bw.write(msg);
                    bw.newLine();
                }
            } catch (IOException e) {
                System.err.println("Error al reescribir archivo de mensajes: " + e.getMessage());
                escritor.println("Error al eliminar mensajes.");
            }
        }

        private void borrarCuenta() throws IOException {
            escritor.println("Estás seguro de borrar tu usuario '" + usuarioActual + "'? (S/N)");
            String confirmacion = lector.readLine();
            if (confirmacion != null && confirmacion.equalsIgnoreCase("S")) {
                eliminarUsuario(usuarioActual);
                escritor.println("Tu cuenta ha sido eliminada. Conexión cerrada.");
                System.out.println("Usuario " + usuarioActual + " ha borrado su cuenta.");
                usuarioActual = null; // Para que el finally no intente cerrar sesión normalmente
                return;
            } else {
                escritor.println("Borrado de cuenta cancelado.");
            }
        }

        private void bloquearUsuario() throws IOException {
            escritor.println("Qué usuario quieres bloquear? (Escribe el nombre de usuario):");
            String usuarioABloquear = lector.readLine().toLowerCase();
            if (usuarioABloquear == null || usuarioABloquear.equalsIgnoreCase("salir")) {
                escritor.println("Bloqueo cancelado.");
                return;
            }
            if (usuarioABloquear.equalsIgnoreCase(usuarioActual)) {
                escritor.println("No puedes bloquearte a ti mismo.");
                return;
            }
            if (!existeUsuario(usuarioABloquear)) {
                escritor.println("El usuario '" + usuarioABloquear + "' no existe.");
                return;
            }

            // Verificar si ya está bloqueado
            if (yaEstaBloqueado(usuarioActual, usuarioABloquear)) {
                escritor.println("Ya has bloqueado a '" + usuarioABloquear + "'.");
                return;
            }

            guardarBloqueo(usuarioActual, usuarioABloquear);
            escritor.println("Has bloqueado a '" + usuarioABloquear + "'. No podrá enviarte mensajes.");
        }

        private void desbloquearUsuario() throws IOException {
            escritor.println("Qué usuario deseas desbloquear? (Escribe el nombre de usuario):");
            String usuarioADesbloquear = lector.readLine().toLowerCase();
            if (usuarioADesbloquear == null || usuarioADesbloquear.equalsIgnoreCase("salir")) {
                escritor.println("Desbloqueo cancelado.");
                return;
            }
            if (!existeUsuario(usuarioADesbloquear)) {
                escritor.println("El usuario '" + usuarioADesbloquear + "' no existe.");
                return;
            }

            // Verificar si no está bloqueado
            if (!yaEstaBloqueado(usuarioActual, usuarioADesbloquear)) {
                escritor.println("No tienes bloqueado a '" + usuarioADesbloquear + "'.");
                return;
            }

            eliminarBloqueo(usuarioActual, usuarioADesbloquear);
            escritor.println("Has desbloqueado a '" + usuarioADesbloquear + "'. Ahora podrá enviarte mensajes.");
        }

        private void verArchivosOtroUsuario(String usuarioDestino) {
            if (usuarioDestino.equalsIgnoreCase(usuarioActual)) {
                escritor.println("No puedes ver tus propios archivos con este comando. Usa 'crear' para crearlos o revisa tu carpeta local.");
                return;
            }
            if (!existeUsuario(usuarioDestino)) {
                escritor.println("El usuario '" + usuarioDestino + "' no existe.");
                return;
            }

            Handler destinoHandler = clientesConectados.get(usuarioDestino);
            if (destinoHandler != null) { // El otro usuario está conectado
                // Enviamos una solicitud interna al otro cliente para que nos envíe su lista
                destinoHandler.escritor.println("SOLICITUD_INTERNA:ENVIAR_MIS_ARCHIVOS:" + usuarioActual);
                escritor.println("Solicitando lista de archivos a " + usuarioDestino + "...");
            } else { // El otro usuario está desconectado
                registrarSolicitudPendiente("LISTA_SOLICITADA", usuarioDestino, usuarioActual, ""); // Contenido vacío para listas
                escritor.println("Usuario " + usuarioDestino + " está offline. Se le solicitarán sus archivos cuando se conecte.");
            }
        }

        private void pedirArchivoOtroUsuario(String usuarioDestino, String nombreArchivo) {
            if (usuarioDestino.equalsIgnoreCase(usuarioActual)) {
                escritor.println("No puedes pedirte archivos a ti mismo.");
                return;
            }
            if (!existeUsuario(usuarioDestino)) {
                escritor.println("El usuario '" + usuarioDestino + "' no existe.");
                return;
            }
            // NO se necesita verificar si el destino está conectado aquí. El flujo INICIANDO_TRANSFERENCIA_ARCHIVO_DE_CLIENTE lo maneja.
            // Si el destino está offline, el servidor guardará el archivo y notificará al destino.

            // Se envía una solicitud interna al otro cliente para que nos envíe el archivo
            // El cliente lo manejará con enviarArchivoAlServidorParaRetransmitir
            Handler destinoHandler = clientesConectados.get(usuarioDestino);
            if (destinoHandler != null) { // El que tiene el archivo está online
                destinoHandler.escritor.println("SOLICITUD_INTERNA:ENVIAR_ARCHIVO:" + usuarioActual + ":" + nombreArchivo);
                escritor.println("Solicitando archivo '" + nombreArchivo + "' a " + usuarioDestino + "...");
            } else { // El que tiene el archivo está offline
                // En este caso, el servidor registra una solicitud al QUE TIENE el archivo (usuarioDestino)
                // para que cuando se conecte, envíe el archivo al SOLICITANTE (usuarioActual).
                registrarSolicitudPendiente("ARCHIVO_SOLICITADO", usuarioDestino, usuarioActual, nombreArchivo);
                escritor.println("Usuario " + usuarioDestino + " está offline. Se le solicitará el archivo cuando se conecte para enviártelo.");
            }
        }

        // NUEVO: Envía un archivo pendiente desde el almacén del servidor al cliente
        private void enviarArchivoPendienteAlCliente(String usuario, String nombreArchivo) {
            Path filePath = Paths.get(CARPETA_DESCARGAS_PENDIENTES_SERVIDOR, usuario, nombreArchivo);

            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                escritor.println("ERROR_TRANSFERENCIA_DE_SERVIDOR: El archivo '" + nombreArchivo + "' no se encontró en tus descargas pendientes.");
                System.err.println("Servidor: Archivo pendiente no encontrado para " + usuario + ": " + nombreArchivo);
                // También, limpia la solicitud pendiente si no se encontró el archivo
                eliminarSolicitudPendienteEspecifica("ARCHIVO_PENDIENTE_DESCARGA", usuario, "", nombreArchivo);
                return;
            }

            try (BufferedReader fileReader = new BufferedReader(new FileReader(filePath.toFile()))) {
                escritor.println("INICIANDO_TRANSFERENCIA_DE_SERVIDOR:" + nombreArchivo);
                String linea;
                while ((linea = fileReader.readLine()) != null) {
                    escritor.println("CONTENIDO_LINEA_DE_SERVIDOR:" + linea);
                }
                escritor.println("TRANSFERENCIA_COMPLETA_DE_SERVIDOR:");
                System.out.println("Servidor: Archivo '" + nombreArchivo + "' enviado desde pendientes a " + usuario + ".");

                // Limpiar después de una descarga exitosa
                Files.deleteIfExists(filePath); // Eliminar el archivo del servidor
                eliminarSolicitudPendienteEspecifica("ARCHIVO_PENDIENTE_DESCARGA", usuario, "", nombreArchivo); // Eliminar la notificación

            } catch (IOException e) {
                System.err.println("Error al enviar archivo pendiente '" + nombreArchivo + "' a " + usuario + ": " + e.getMessage());
                escritor.println("ERROR_TRANSFERENCIA_DE_SERVIDOR: Error al leer el archivo pendiente.");
            }
        }


        // --- Métodos de Persistencia (Todos son synchronized para seguridad de archivos) ---

        private synchronized boolean existeUsuario(String usuario) {
            try (BufferedReader br = new BufferedReader(new FileReader(ARCHIVO_USUARIOS))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    if (linea.startsWith(usuario + ":")) {
                        return true;
                    }
                }
            } catch (IOException e) {
                System.err.println("Error al leer archivo de usuarios: " + e.getMessage());
            }
            return false;
        }

        private synchronized boolean validarCredenciales(String usuario, String pin) {
            try (BufferedReader br = new BufferedReader(new FileReader(ARCHIVO_USUARIOS))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    if (linea.equals(usuario + ":" + pin)) {
                        return true;
                    }
                }
            } catch (IOException e) {
                System.err.println("Error al leer archivo de usuarios: " + e.getMessage());
            }
            return false;
        }

        private synchronized void guardarUsuario(String usuario, String pin) {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_USUARIOS, true))) { // true para append
                bw.write(usuario + ":" + pin);
                bw.newLine();
            } catch (IOException e) {
                System.err.println("Error al guardar usuario: " + e.getMessage());
            }
        }

        private synchronized void eliminarUsuario(String usuarioAEliminar) {
            List<String> lineasRestantes = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(ARCHIVO_USUARIOS))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    if (!linea.startsWith(usuarioAEliminar + ":")) {
                        lineasRestantes.add(linea);
                    }
                }
            } catch (IOException e) {
                System.err.println("Error al leer archivo de usuarios para eliminar: " + e.getMessage());
            }

            try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_USUARIOS, false))) { // false para sobreescribir
                for (String linea : lineasRestantes) {
                    bw.write(linea);
                    bw.newLine();
                }
            } catch (IOException e) {
                System.err.println("Error al reescribir archivo de usuarios: " + e.getMessage());
            }
            // También eliminar mensajes, bloqueos, solicitudes pendientes y archivos de descarga relacionados con este usuario
            eliminarMensajesDeUsuario(usuarioAEliminar);
            eliminarBloqueosDeUsuario(usuarioAEliminar);
            eliminarSolicitudesPendientesDeUsuario(usuarioAEliminar);
            eliminarCarpetaDescargasPendientes(usuarioAEliminar); // NUEVO
        }

        private synchronized void guardarMensajeOffline(String origen, String destino, String mensaje) {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_MENSAJES, true))) {
                bw.write(destino + ":De " + origen + ": " + mensaje);
                bw.newLine();
            } catch (IOException e) {
                System.err.println("Error al guardar mensaje offline: " + e.getMessage());
            }
        }

        private synchronized List<String> obtenerMensajesParaUsuario(String usuario) {
            List<String> mensajes = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(ARCHIVO_MENSAJES))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    if (linea.startsWith(usuario + ":")) {
                        mensajes.add(linea.substring((usuario + ":").length())); // Quitar el prefijo del usuario
                    }
                }
            } catch (IOException e) {
                System.err.println("Error al leer mensajes: " + e.getMessage());
            }
            return mensajes;
        }

        private synchronized void eliminarMensajesDeUsuario(String usuarioAEliminar) {
            List<String> lineasRestantes = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(ARCHIVO_MENSAJES))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    // Si no es un mensaje PARA el usuario a eliminar ni un mensaje DE el usuario a eliminar
                    if (!linea.startsWith(usuarioAEliminar + ":") && !linea.contains(":De " + usuarioAEliminar + ":")) {
                        lineasRestantes.add(linea);
                    }
                }
            } catch (IOException e) {
                System.err.println("Error al leer mensajes para eliminar por usuario: " + e.getMessage());
            }
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_MENSAJES, false))) {
                for (String linea : lineasRestantes) {
                    bw.write(linea);
                    bw.newLine();
                }
            } catch (IOException e) {
                System.err.println("Error al reescribir archivo de mensajes: " + e.getMessage());
            }
        }

        private synchronized void guardarBloqueo(String bloqueador, String bloqueado) {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_BLOQUEADOS, true))) {
                bw.write(bloqueador + ":" + bloqueado);
                bw.newLine();
            } catch (IOException e) {
                System.err.println("Error al guardar bloqueo: " + e.getMessage());
            }
        }

        private synchronized void eliminarBloqueo(String bloqueador, String desbloqueado) {
            List<String> lineasRestantes = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(ARCHIVO_BLOQUEADOS))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    if (!linea.equals(bloqueador + ":" + desbloqueado)) {
                        lineasRestantes.add(linea);
                    }
                }
            } catch (IOException e) {
                System.err.println("Error al leer bloqueos para eliminar: " + e.getMessage());
            }
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_BLOQUEADOS, false))) {
                for (String linea : lineasRestantes) {
                    bw.write(linea);
                    bw.newLine();
                }
            } catch (IOException e) {
                System.err.println("Error al reescribir archivo de bloqueos: " + e.getMessage());
            }
        }

        private synchronized boolean estaBloqueado(String bloqueador, String bloqueado) {
            try (BufferedReader br = new BufferedReader(new FileReader(ARCHIVO_BLOQUEADOS))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    if (linea.equals(bloqueador + ":" + bloqueado)) {
                        return true;
                    }
                }
            } catch (IOException e) {
                System.err.println("Error al leer archivo de bloqueados: " + e.getMessage());
            }
            return false;
        }

        private synchronized boolean yaEstaBloqueado(String bloqueador, String usuarioBloqueado) {
            try (BufferedReader br = new BufferedReader(new FileReader(ARCHIVO_BLOQUEADOS))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    if (linea.equals(bloqueador + ":" + usuarioBloqueado)) {
                        return true;
                    }
                }
            } catch (IOException e) {
                System.err.println("Error al verificar bloqueo: " + e.getMessage());
            }
            return false;
        }

        private synchronized void eliminarBloqueosDeUsuario(String usuarioAEliminar) {
            List<String> lineasRestantes = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(ARCHIVO_BLOQUEADOS))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    // Si el usuario no es el bloqueador ni el bloqueado
                    String[] partes = linea.split(":");
                    if (partes.length == 2 && !partes[0].equalsIgnoreCase(usuarioAEliminar) && !partes[1].equalsIgnoreCase(usuarioAEliminar)) {
                        lineasRestantes.add(linea);
                    }
                }
            } catch (IOException e) {
                System.err.println("Error al leer bloqueos para eliminar por usuario: " + e.getMessage());
            }
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_BLOQUEADOS, false))) {
                for (String linea : lineasRestantes) {
                    bw.write(linea);
                    bw.newLine();
                }
            } catch (IOException e) {
                System.err.println("Error al reescribir archivo de bloqueos: " + e.getMessage());
            }
        }

        // --- Manejo de Solicitudes Pendientes (para usuarios offline) ---

        // Método auxiliar para registrar solicitudes pendientes
        private void registrarSolicitudPendiente(String tipo, String destino, String origen, String contenido) {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_SOLICITUDES_PENDIENTES, true))) { // true para append
                // Formato de guardado: TIPO:DESTINO:ORIGEN:CONTENIDO
                bw.write(tipo + ":" + destino.toLowerCase() + ":" + origen.toLowerCase() + ":" + contenido);
                bw.newLine();
            } catch (IOException e) {
                System.err.println("Error al registrar solicitud pendiente: " + e.getMessage());
            }
        }

        // NUEVO: Eliminar una solicitud pendiente específica (útil después de descargar un archivo)
        private synchronized void eliminarSolicitudPendienteEspecifica(String tipo, String destino, String origen, String contenido) {
            List<String> lineasRestantes = new ArrayList<>();
            boolean eliminada = false;
            try (BufferedReader br = new BufferedReader(new FileReader(ARCHIVO_SOLICITUDES_PENDIENTES))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    // Solo elimina la primera ocurrencia que coincida
                    if (!eliminada && linea.equalsIgnoreCase(tipo + ":" + destino.toLowerCase() + ":" + origen.toLowerCase() + ":" + contenido)) {
                        eliminada = true;
                    } else {
                        lineasRestantes.add(linea);
                    }
                }
            } catch (IOException e) {
                System.err.println("Error al leer solicitudes pendientes para eliminación específica: " + e.getMessage());
            }
            if (eliminada) {
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_SOLICITUDES_PENDIENTES, false))) {
                    for (String l : lineasRestantes) {
                        bw.write(l);
                        bw.newLine();
                    }
                } catch (IOException e) {
                    System.err.println("Error al reescribir solicitudes pendientes después de eliminar: " + e.getMessage());
                }
            }
        }

        // Elimina las solicitudes pendientes donde el usuario es el origen o el destino
        private synchronized void eliminarSolicitudesPendientesDeUsuario(String usuarioAEliminar) {
            List<String> lineasRestantes = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(ARCHIVO_SOLICITUDES_PENDIENTES))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    String[] partes = linea.split(":", 4); // Split con límite para el contenido
                    if (partes.length >= 4) {
                        // Si el usuario no es el DESTINO ni el ORIGEN de la solicitud
                        if (!partes[1].equalsIgnoreCase(usuarioAEliminar) && !partes[2].equalsIgnoreCase(usuarioAEliminar)) {
                            lineasRestantes.add(linea);
                        }
                    } else { // Si la línea está mal formateada, la conservamos por seguridad o la ignoramos si no es crucial
                        lineasRestantes.add(linea);
                    }
                }
            } catch (IOException e) {
                System.err.println("Error al leer solicitudes pendientes para eliminar por usuario: " + e.getMessage());
            }
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_SOLICITUDES_PENDIENTES, false))) {
                for (String linea : lineasRestantes) {
                    bw.write(linea);
                    bw.newLine();
                }
            } catch (IOException e) {
                System.err.println("Error al reescribir archivo de solicitudes pendientes: " + e.getMessage());
            }
        }

        // Procesa y retransmite mensajes/solicitudes pendientes al usuario que acaba de iniciar sesión
        private synchronized void procesarSolicitudesPendientes(String usuario) {
            List<String> lineas = new ArrayList<>();
            List<String> lineasRestantes = new ArrayList<>();
            boolean encontradas = false;

            try (BufferedReader br = new BufferedReader(new FileReader(ARCHIVO_SOLICITUDES_PENDIENTES))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    lineas.add(linea);
                }
            } catch (IOException e) {
                System.err.println("Error al leer solicitudes pendientes: " + e.getMessage());
                return;
            }

            // Procesar las solicitudes
            for (String linea : lineas) {
                // Usamos split con límite 4: [TIPO, DESTINO, ORIGEN, CONTENIDO]
                String[] partes = linea.split(":", 4);

                // Solo procesamos líneas válidas (que tienen al menos 4 partes) y que son para el usuario actual
                if (partes.length >= 4 && partes[1].equalsIgnoreCase(usuario)) {

                    // 1. Mensajes (y Notificaciones)
                    if (partes[0].equalsIgnoreCase("MENSAJE")) {
                        escritor.println("NOTIFICACION_PENDIENTE: Mensaje de " + partes[2] + ": " + partes[3]);
                        encontradas = true;

                        // 2. Solicitud de Archivo (el servidor le pide al cliente que lo envíe)
                    } else if (partes[0].equalsIgnoreCase("ARCHIVO_SOLICITADO")) {
                        escritor.println("SOLICITUD_INTERNA:ENVIAR_ARCHIVO:" + partes[2] + ":" + partes[3]);
                        encontradas = true;

                        // 3. Solicitud de Lista de Archivos (el servidor le pide al cliente que la envíe)
                    } else if (partes[0].equalsIgnoreCase("LISTA_SOLICITADA")) {
                        escritor.println("SOLICITUD_INTERNA:ENVIAR_MIS_ARCHIVOS:" + partes[2]);
                        encontradas = true;

                        // 4. Lista de Archivos RECIBIDA (el servidor se la entrega al solicitante que ya está online)
                    } else if (partes[0].equalsIgnoreCase("LISTA_RECIBIDA")) {
                        escritor.println("ARCHIVOS_DE:" + partes[2] + ":" + partes[3]);
                        encontradas = true;
                        // 5. NUEVO: Notificación de archivo que el servidor guardó para el cliente
                    } else if (partes[0].equalsIgnoreCase("ARCHIVO_PENDIENTE_DESCARGA")) {
                        escritor.println("NOTIFICACION_ARCHIVO_PENDIENTE: Tienes un archivo pendiente: '" + partes[3] + "' de " + partes[2] + ".");
                        encontradas = true;
                        // OJO: No se elimina aquí la solicitud de inmediato. Se elimina cuando el cliente lo descarga.
                        // La línea se añade a lineasRestantes para que se conserve hasta que el cliente decida descargarlo.
                        lineasRestantes.add(linea); // Conservar esta línea para reescribir, no se elimina todavía
                        continue; // No añadirla a lineasRestantes de nuevo al final del loop
                    }
                }
                lineasRestantes.add(linea); // Conservar las líneas que no son para este usuario o que son notificaciones pendientes de descarga
            }

            if (encontradas) {
                // Reescribe el archivo de solicitudes pendientes sin las que ya fueron procesadas
                // (excepto las NOTIFICACION_ARCHIVO_PENDIENTE que se procesan al descargar)
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_SOLICITUDES_PENDIENTES, false))) {
                    for (String linea : lineasRestantes) {
                        bw.write(linea);
                        bw.newLine();
                    }
                } catch (IOException e) {
                    System.err.println("Error al reescribir solicitudes pendientes: " + e.getMessage());
                }
            }
        }

        // NUEVO: Eliminar la carpeta de descargas pendientes de un usuario
        private void eliminarCarpetaDescargasPendientes(String usuarioAEliminar) {
            Path userDownloadsPath = Paths.get(CARPETA_DESCARGAS_PENDIENTES_SERVIDOR, usuarioAEliminar);
            if (Files.exists(userDownloadsPath)) {
                try {
                    Files.walk(userDownloadsPath)
                            .sorted(java.util.Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                    System.out.println("Carpeta de descargas pendientes del servidor eliminada para " + usuarioAEliminar);
                } catch (IOException e) {
                    System.err.println("Error al eliminar carpeta de descargas pendientes para " + usuarioAEliminar + ": " + e.getMessage());
                }
            }
        }


        private void cerrarSesion() {
            if (usuarioActual != null) {
                clientesConectados.remove(usuarioActual);
                System.out.println("Usuario " + usuarioActual + " ha cerrado sesión.");
            }
            // Asegurarse de cerrar el archivo temporal si estaba en medio de una transferencia
            if (archivoEnEscrituraTemporal != null) {
                try {
                    archivoEnEscrituraTemporal.close();
                    // Opcional: eliminar el archivo parcial si el cliente se desconectó a mitad de envío
                    Path archivoParcial = Paths.get(CARPETA_DESCARGAS_PENDIENTES_SERVIDOR, archivoDestinoTransferencia, nombreArchivoEnTransito);
                    try { Files.deleteIfExists(archivoParcial); } catch (IOException e) { System.err.println("Error al eliminar archivo parcial tras desconexión: " + e.getMessage()); }
                } catch (IOException e) {
                    System.err.println("Error al cerrar BufferedWriter temporal: " + e.getMessage());
                }
            }
            try {
                if (lector != null) lector.close();
                if (escritor != null) escritor.close();
                if (clienteSocket != null) clienteSocket.close();
            } catch (IOException e) {
                System.err.println("Error al cerrar recursos del cliente: " + e.getMessage());
            }
        }
    }
}