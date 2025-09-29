import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class cliente2025 {

    // Se inicializa vacío, se llenará tras el login
    private static String usuarioActual = null;
    // La carpeta se construirá como "mis_archivos_" + usuarioActual
    private static String MI_CARPETA_COMPARTIDA = null;

    public static void main(String[] args) throws IOException {

        Socket socket = new Socket("localhost", 8080);
        PrintWriter escritor = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader lector = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in));

        String mensajeServidor;
        boolean enModoTransferencia = false; // Transferencia de cliente a cliente
        String remitenteArchivo = "";
        String nombreArchivoEnTransferencia = "";
        List<String> contenidoArchivo = new ArrayList<>();

        boolean enModoTransferenciaDesdeServidor = false; // NUEVO: Transferencia desde el servidor
        String nombreArchivoEnTransferenciaDesdeServidor = ""; // NUEVO
        List<String> contenidoArchivoDesdeServidor = new ArrayList<>(); // NUEVO

        try {
            while ((mensajeServidor = lector.readLine()) != null) {

                // *** LÓGICA CLAVE: DETECTAR INICIO DE SESIÓN EXITOSO PARA INICIALIZAR LA CARPETA ***
                if ((mensajeServidor.contains("Firma exitosa. ¡Bienvenido ") ||
                        mensajeServidor.contains("Registro exitoso. Ahora tu sesión está activa.")) &&
                        usuarioActual == null) {

                    String[] partes = mensajeServidor.split(" ");
                    // Asume que el nombre de usuario es la última palabra sin puntuación
                    String nombreUsuario = partes[partes.length - 1].replace("!", "").replace(".", "");

                    usuarioActual = nombreUsuario.toLowerCase(); // Asegurarse que esté en minúsculas
                    MI_CARPETA_COMPARTIDA = "mis_archivos_" + usuarioActual;

                    // Crear la carpeta personalizada si no existe
                    File sharedDir = new File(MI_CARPETA_COMPARTIDA);
                    if (!sharedDir.exists()) {
                        sharedDir.mkdirs();
                        System.out.println("Carpeta de archivos compartidos: '" + MI_CARPETA_COMPARTIDA + "' creada.");
                    } else {
                        System.out.println("Carpeta de archivos compartidos: '" + MI_CARPETA_COMPARTIDA + "' ya existe.");
                    }
                }

                // --- MANEJO DE COMANDOS INTERNOS DEL SERVIDOR (AUTOMÁTICOS) ---
                // Solo procesar solicitudes internas si el usuario ya ha iniciado sesión
                if (usuarioActual != null && MI_CARPETA_COMPARTIDA != null && mensajeServidor.startsWith("SOLICITUD_INTERNA:")) {
                    String[] partes = mensajeServidor.split(":", 3);
                    String tipoSolicitud = partes[1];
                    String solicitante = partes[2];

                    if (tipoSolicitud.equalsIgnoreCase("ENVIAR_MIS_ARCHIVOS")) {
                        String listaArchivos = obtenerListaArchivosLocalmente(MI_CARPETA_COMPARTIDA);
                        escritor.println("RESPUESTA_ARCHIVOS:" + solicitante + ":" + listaArchivos);
                        System.out.println("[INFO] Enviando mi lista de archivos de '" + MI_CARPETA_COMPARTIDA + "' a " + solicitante + "...");
                    } else if (tipoSolicitud.equalsIgnoreCase("ENVIAR_ARCHIVO")) {
                        String[] archivoPartes = solicitante.split(":", 2);
                        String solicitanteOriginal = archivoPartes[0];
                        String nombreArchivo = archivoPartes[1];

                        System.out.println("[INFO] Enviando archivo '" + nombreArchivo + "' de '" + MI_CARPETA_COMPARTIDA + "' a " + solicitanteOriginal + "...");
                        enviarArchivoAlServidorParaRetransmitir(escritor, nombreArchivo, solicitanteOriginal, MI_CARPETA_COMPARTIDA);
                    }
                    continue; // Ya se procesó un comando interno, esperar el siguiente.
                }

                // --- Lógica de Transferencia de Archivos (desde OTRO USUARIO vía servidor) ---
                if (mensajeServidor.startsWith("INICIANDO_TRANSFERENCIA_DE_USUARIO:")) {
                    String[] partes = mensajeServidor.split(":", 3);
                    remitenteArchivo = partes[1];
                    nombreArchivoEnTransferencia = partes[2];
                    System.out.println("Servidor: " + mensajeServidor);
                    System.out.println("Transferencia de '" + nombreArchivoEnTransferencia + "' de " + remitenteArchivo + " iniciada...");
                    enModoTransferencia = true;
                    contenidoArchivo.clear();
                    continue;
                }
                if (enModoTransferencia) {
                    if (mensajeServidor.startsWith("CONTENIDO_LINEA_DE_USUARIO:")) {
                        String prefijo = "CONTENIDO_LINEA_DE_USUARIO:" + remitenteArchivo + ":";
                        if (mensajeServidor.startsWith(prefijo)) {
                            String linea = mensajeServidor.substring(prefijo.length());
                            contenidoArchivo.add(linea);
                        }
                    } else if (mensajeServidor.startsWith("TRANSFERENCIA_COMPLETA_DE_USUARIO:")) {
                        guardarArchivoLocal(nombreArchivoEnTransferencia, contenidoArchivo);
                        System.out.println("Transferencia completada. Archivo guardado como '" + nombreArchivoEnTransferencia + "' en tu directorio local.");
                        enModoTransferencia = false;
                        remitenteArchivo = "";
                        nombreArchivoEnTransferencia = "";
                    } else if (mensajeServidor.startsWith("ERROR_TRANSFERENCIA_DE_USUARIO:")) {
                        System.err.println("Error en la transferencia: " + mensajeServidor.substring("ERROR_TRANSFERENCIA_DE_USUARIO:".length()));
                        enModoTransferencia = false;
                        remitenteArchivo = "";
                        nombreArchivoEnTransferencia = "";
                    }
                    continue; // Ya se procesó una línea de transferencia, esperar la siguiente.
                }

                // --- NUEVO: Lógica de Transferencia de Archivos (desde el SERVIDOR) ---
                if (mensajeServidor.startsWith("INICIANDO_TRANSFERENCIA_DE_SERVIDOR:")) {
                    String[] partes = mensajeServidor.split(":", 2); // Servidor solo envía nombre de archivo
                    nombreArchivoEnTransferenciaDesdeServidor = partes[1];
                    System.out.println("Servidor: " + mensajeServidor); // Mostrar el mensaje original del servidor
                    System.out.println("Transferencia de '" + nombreArchivoEnTransferenciaDesdeServidor + "' desde el servidor iniciada...");
                    enModoTransferenciaDesdeServidor = true;
                    contenidoArchivoDesdeServidor.clear();
                    continue;
                }
                if (enModoTransferenciaDesdeServidor) {
                    if (mensajeServidor.startsWith("CONTENIDO_LINEA_DE_SERVIDOR:")) {
                        String prefijo = "CONTENIDO_LINEA_DE_SERVIDOR:";
                        if (mensajeServidor.startsWith(prefijo)) {
                            String linea = mensajeServidor.substring(prefijo.length());
                            contenidoArchivoDesdeServidor.add(linea);
                        } else {
                            System.err.println("Error: Formato de línea de contenido inesperado desde servidor: " + mensajeServidor);
                        }
                    } else if (mensajeServidor.startsWith("TRANSFERENCIA_COMPLETA_DE_SERVIDOR:")) {
                        // Aquí guardamos el archivo en la carpeta PERSONAL del usuario, no en la raíz.
                        guardarArchivoEnCarpetaCompartida(nombreArchivoEnTransferenciaDesdeServidor, contenidoArchivoDesdeServidor);
                        System.out.println("Transferencia completada. Archivo guardado como '" + nombreArchivoEnTransferenciaDesdeServidor + "' en tu carpeta compartida: '" + MI_CARPETA_COMPARTIDA + "'.");
                        enModoTransferenciaDesdeServidor = false;
                        nombreArchivoEnTransferenciaDesdeServidor = "";
                    } else if (mensajeServidor.startsWith("ERROR_TRANSFERENCIA_DE_SERVIDOR:")) {
                        System.err.println("Error en la transferencia desde el servidor: " + mensajeServidor.substring("ERROR_TRANSFERENCIA_DE_SERVIDOR:".length()));
                        enModoTransferenciaDesdeServidor = false;
                        nombreArchivoEnTransferenciaDesdeServidor = "";
                    }
                    continue; // Ya se procesó una línea de transferencia, esperar la siguiente.
                }


                // --- Lógica de Recepción de Listas de Archivos de Otro Usuario (Ahora también de LISTA_RECIBIDA) ---
                if (mensajeServidor.startsWith("ARCHIVOS_DE:")) {
                    String[] partes = mensajeServidor.split(":", 3);
                    if (partes.length == 3) {
                        String origen = partes[1];
                        String listaArchivosStr = partes[2];
                        if (listaArchivosStr.isEmpty()) {
                            System.out.println("El usuario " + origen + " no tiene archivos compartidos.");
                        } else {
                            System.out.println("Archivos de '" + origen + "':");
                            String[] archivos = listaArchivosStr.split(",");
                            for (String archivo : archivos) {
                                System.out.println("- " + archivo);
                            }
                        }
                    }
                    continue; // Ya se procesó una lista de archivos, esperar el siguiente.
                }

                // --- Lógica de Notificaciones de Mensajes/Solicitudes Pendientes ---
                if (mensajeServidor.startsWith("NOTIFICACION_PENDIENTE:")) {
                    System.out.println(mensajeServidor.substring("NOTIFICACION_PENDIENTE:".length()));
                    continue;
                }
                if (mensajeServidor.startsWith("MENSAJE_NUEVO_DIRECTO:")) {
                    System.out.println("\n--- NUEVO MENSAJE ---");
                    System.out.println(mensajeServidor.substring("MENSAJE_NUEVO_DIRECTO:".length()));
                    System.out.println("---------------------");
                    continue;
                }
                // NUEVO: Notificación de archivo pendiente para descargar desde el servidor
                if (mensajeServidor.startsWith("NOTIFICACION_ARCHIVO_PENDIENTE:")) {
                    System.out.println("\n--- ARCHIVO PENDIENTE DE DESCARGA ---");
                    System.out.println(mensajeServidor.substring("NOTIFICACION_ARCHIVO_PENDIENTE:".length()));
                    System.out.println("Usa el comando 'descargar <nombre_archivo>' para obtenerlo.");
                    System.out.println("-------------------------------------");
                    continue;
                }

                // --- Lógica de Comando para Crear Archivo (CLIENTE LOCAL) ---
                if (usuarioActual != null && MI_CARPETA_COMPARTIDA != null && mensajeServidor.startsWith("CREAR_ARCHIVO_LOCAL:")) {
                    String nombreArchivo = mensajeServidor.substring("CREAR_ARCHIVO_LOCAL:".length());
                    // Aseguramos que el archivo tenga extensión .txt si no se la puso el usuario
                    if (!nombreArchivo.toLowerCase().endsWith(".txt")) {
                        nombreArchivo += ".txt";
                    }
                    File nuevoArchivo = new File(MI_CARPETA_COMPARTIDA, nombreArchivo);
                    if (nuevoArchivo.exists()) {
                        System.out.println("El archivo '" + nombreArchivo + "' ya existe en tu carpeta '" + MI_CARPETA_COMPARTIDA + "'.");
                    } else {
                        System.out.println("Escribe el contenido para '" + nombreArchivo + "' (termina con una línea vacía):");
                        try (BufferedWriter bw = new BufferedWriter(new FileWriter(nuevoArchivo))) {
                            String lineaContenido;
                            while (!(lineaContenido = teclado.readLine()).isEmpty()) {
                                bw.write(lineaContenido);
                                bw.newLine();
                            }
                            System.out.println("Archivo '" + nombreArchivo + "' creado y guardado en '" + MI_CARPETA_COMPARTIDA + "'.");
                        } catch (IOException e) {
                            System.err.println("Error al crear el archivo local en '" + MI_CARPETA_COMPARTIDA + "': " + e.getMessage());
                        }
                    }
                    System.out.println("LISTO PARA COMANDO"); // Siempre enviar "LISTO PARA COMANDO" después de una acción local
                    continue;
                }

                // --- Lógica Normal de Interacción ---
                System.out.println("Servidor: " + mensajeServidor);

                // --- Determinación de si se necesita entrada del usuario ---
                // Se agregó la condición `mensajeServidor.contains("Opción no válida")` para el fix del loop de inicio
                if (mensajeServidor.contains("LISTO PARA COMANDO") ||
                        mensajeServidor.contains("Escribe tu nombre de usuario:") ||
                        mensajeServidor.contains("Escribe tu PIN de 4 digitos:") ||
                        mensajeServidor.contains("¿Quieres registrarte") ||
                        mensajeServidor.contains("Escribe tu PIN de 4 digitos para firmar:") ||
                        mensajeServidor.contains("Escribe el nombre del usuario") ||
                        mensajeServidor.contains("Escribe el mensaje:") ||
                        mensajeServidor.contains("Escribe tu intento #") ||
                        mensajeServidor.contains("¿Quieres jugar otra vez?") ||
                        mensajeServidor.contains("¿Quieres eliminar mensajes") ||
                        mensajeServidor.contains("Estás seguro de borrar tu usuario") ||
                        mensajeServidor.contains("Qué usuario quieres bloquear") ||
                        mensajeServidor.contains("Qué usuario deseas desbloquear") ||
                        mensajeServidor.contains("Opción no válida")) { // <<-- FIX DEL DEADLOCK DE INICIO

                    System.out.print("Tú: ");
                    String entradaUsuario = teclado.readLine();

                    if (entradaUsuario == null) {
                        System.out.println("Entrada de usuario nula, cerrando conexión.");
                        break;
                    }

                    // NUEVA LÓGICA: Procesar el comando 'descargar' LOCALMENTE antes de enviarlo
                    if (entradaUsuario.toLowerCase().startsWith("descargar ")) {
                        String nombreArchivoADescargar = entradaUsuario.substring(10).trim();
                        // El cliente envía el comando 'DESCARGAR_ARCHIVO_DE_SERVIDOR' al servidor
                        // para que el servidor inicie la transferencia desde su almacén.
                        escritor.println("DESCARGAR_ARCHIVO_DE_SERVIDOR:" + nombreArchivoADescargar);
                        System.out.println("Solicitando al servidor el archivo '" + nombreArchivoADescargar + "'...");
                        // No es necesario un continue aquí, el bucle principal continuará esperando el siguiente mensaje del servidor.
                        // El servidor responderá con INICIANDO_TRANSFERENCIA_DE_SERVIDOR si todo va bien.
                    } else {
                        // Si no es un comando 'descargar', se envía el comando tal cual al servidor.
                        escritor.println(entradaUsuario);
                    }

                    if (entradaUsuario.equalsIgnoreCase("salir") || entradaUsuario.equalsIgnoreCase("cerrar")) {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error de E/S con el servidor: " + e.getMessage());
        } finally {
            try {
                if (socket != null && !socket.isClosed()) socket.close();
                if (lector != null) lector.close();
                if (escritor != null) escritor.close();
                if (teclado != null) teclado.close();
            } catch (IOException e) {
                System.err.println("Error al cerrar recursos del cliente: " + e.getMessage());
            }
        }
    }

    private static String obtenerListaArchivosLocalmente(String carpetaCompartida) {
        File sharedDir = new File(carpetaCompartida);
        if (!sharedDir.exists()) {
            return "";
        }
        File[] archivos = sharedDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".txt"));

        if (archivos == null || archivos.length == 0) {
            return "";
        }

        StringBuilder lista = new StringBuilder();
        for (int i = 0; i < archivos.length; i++) {
            lista.append(archivos[i].getName());
            if (i < archivos.length - 1) {
                lista.append(",");
            }
        }
        return lista.toString();
    }

    private static void enviarArchivoAlServidorParaRetransmitir(PrintWriter escritor, String nombreArchivo, String solicitante, String carpetaCompartida) {
        File archivoAEnviar = new File(carpetaCompartida, nombreArchivo);

        if (!archivoAEnviar.exists() || !archivoAEnviar.isFile() || !nombreArchivo.toLowerCase().endsWith(".txt")) {
            escritor.println("ERROR_TRANSFERENCIA_DE_CLIENTE:" + solicitante + ":El archivo '" + nombreArchivo + "' no existe o no es un archivo de texto en mi carpeta compartida.");
            System.err.println("Error: No se puede enviar el archivo '" + nombreArchivo + "'. No existe o no es un .txt en '" + carpetaCompartida + "'.");
            return;
        }

        try (BufferedReader fileReader = new BufferedReader(new FileReader(archivoAEnviar))) {
            escritor.println("INICIANDO_TRANSFERENCIA_ARCHIVO_DE_CLIENTE:" + solicitante + ":" + nombreArchivo);
            String linea;
            while ((linea = fileReader.readLine()) != null) {
                escritor.println("CONTENIDO_LINEA_ARCHIVO_DE_CLIENTE:" + solicitante + ":" + linea);
            }
            escritor.println("TRANSFERENCIA_COMPLETA_DE_CLIENTE:" + solicitante);
            System.out.println("Archivo '" + nombreArchivo + "' enviado al servidor para " + solicitante + " desde '" + carpetaCompartida + "'.");
        } catch (IOException e) {
            System.err.println("Error al leer y enviar el archivo " + nombreArchivo + ": " + e.getMessage());
            escritor.println("ERROR_TRANSFERENCIA_DE_CLIENTE:" + solicitante + ":Error de lectura al enviar el archivo.");
        }
    }

    /**
     * Guarda el contenido de un archivo recibido (DE OTRO CLIENTE vía servidor)
     * en el directorio raíz de ejecución del cliente.
     * @param nombreArchivo El nombre del archivo a guardar.
     * @param contenido La lista de líneas de texto del archivo.
     */
    private static void guardarArchivoLocal(String nombreArchivo, List<String> contenido) {
        // Los archivos descargados de otros clientes se guardarán en el directorio raíz del cliente (donde se ejecuta).
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(nombreArchivo))) {
            for (String linea : contenido) {
                bw.write(linea);
                bw.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error al guardar el archivo local " + nombreArchivo + ": " + e.getMessage());
        }
    }

    /**
     * NUEVO: Guarda el contenido de un archivo recibido DEL SERVIDOR
     * en la carpeta compartida personalizada del cliente (mis_archivos_usuario).
     * @param nombreArchivo El nombre del archivo a guardar.
     * @param contenido La lista de líneas de texto del archivo.
     */
    private static void guardarArchivoEnCarpetaCompartida(String nombreArchivo, List<String> contenido) {
        if (MI_CARPETA_COMPARTIDA == null || usuarioActual == null) {
            System.err.println("Error: La carpeta compartida del usuario no está configurada. No se pudo guardar el archivo.");
            return;
        }
        File archivoAGuardar = new File(MI_CARPETA_COMPARTIDA, nombreArchivo);
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(archivoAGuardar))) {
            for (String linea : contenido) {
                bw.write(linea);
                bw.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error al guardar el archivo '" + nombreArchivo + "' en la carpeta '" + MI_CARPETA_COMPARTIDA + "': " + e.getMessage());
        }
    }
}