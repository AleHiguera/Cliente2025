import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

public class servidor2025 {
    private static final String ARCHIVO_USUARIOS = "cuentas.txt";
    private static final String ARCHIVO_MENSAJES = "mensajes.txt";

    private static final int MENSAJES_POR_PAGINA = 10;

    public static void main(String[] args) throws IOException {
        ServerSocket server = new ServerSocket(8080);
        System.out.println("Servidor iniciado. Esperando cliente...");
        Socket cliente = server.accept();

        PrintWriter escritor = new PrintWriter(cliente.getOutputStream(), true);
        BufferedReader lector = new BufferedReader(new InputStreamReader(cliente.getInputStream()));

        boolean sesionActiva = false;
        String usuario = "";

        while (true) {
            if (!sesionActiva) {
                escritor.println("Bienvenido. ¿Quieres registrarte (R) o firmar (F)? Escribe salir para terminar.");
                String opcion = lector.readLine();

                if (opcion == null) {
                    escritor.println("Conexión cerrada.");
                    break;
                }

                opcion = opcion.trim();
                if (opcion.equalsIgnoreCase("salir")) {
                    escritor.println("Cerrando servidor. ¡Adiós!");
                    break;
                }

                if (opcion.equalsIgnoreCase("R")) {
                    escritor.println("Escribe tu nombre de usuario:");
                    usuario = lector.readLine().trim();

                    if (usuario.isEmpty() || !usuario.matches(".*[a-zA-Z].*")) {
                        escritor.println("Nombre de usuario inválido. Debe contener al menos una letra.");
                        continue;
                    }

                    if (usuarioExiste(usuario)) {
                        escritor.println("El usuario ya existe. Intenta firmar.");
                        escritor.println("Escribe tu PIN de 4 digitos para firmar:");
                        String pin = lector.readLine().trim();

                        if (validarUsuario(usuario, pin)) {
                            escritor.println("Firma exitosa. ¡Bienvenido " + usuario + "!");
                            sesionActiva = true;
                        } else {
                            escritor.println("Usuario o PIN incorrecto.");
                        }
                    } else {
                        escritor.println("Escribe tu PIN de 4 digitos:");
                        String pin = lector.readLine().trim();

                        if (pin.matches("\\d{4}")) {
                            guardarUsuario(usuario, pin);
                            escritor.println("Registro exitoso. Ahora tu sesión está activa.");
                            sesionActiva = true;
                        } else {
                            escritor.println("PIN inválido. Debe ser exactamente 4 números.");
                        }
                    }
                } else if (opcion.equalsIgnoreCase("F")) {
                    escritor.println("Escribe tu nombre de usuario:");
                    usuario = lector.readLine().trim();

                    escritor.println("Escribe tu PIN de 4 digitos:");
                    String pin = lector.readLine().trim();

                    if (validarUsuario(usuario, pin)) {
                        escritor.println("Firma exitosa. ¡Bienvenido " + usuario + "!");
                        sesionActiva = true;
                    } else {
                        escritor.println("Usuario o PIN incorrecto.");
                    }
                } else {
                    escritor.println("Opcion invalida.");
                }
            } else {

                escritor.println("Escribe 'cerrar' para cerrar sesión, 'jugar' para comenzar el juego, 'usuarios' para ver la lista de usuarios, 'mensaje' para dejar un mensaje, 'leer' para ver tus mensajes, 'eliminar' para borrar mensajes, o 'bcuenta' para borrar tu usuario.");
                String accion = lector.readLine();

                if (accion == null) {
                    escritor.println("Conexión cerrada.");
                    break;
                }

                accion = accion.trim();
                if (accion.equalsIgnoreCase("cerrar")) {
                    escritor.println("Sesión cerrada de " + usuario + ".");
                    usuario = "";
                    sesionActiva = false;
                } else if (accion.equalsIgnoreCase("jugar")) {
                    jugar(escritor, lector);
                } else if (accion.equalsIgnoreCase("usuarios")) {
                    String listaUsuarios = obtenerListaUsuarios();
                    escritor.println("Usuarios registrados:\n" + listaUsuarios);
                } else if (accion.equalsIgnoreCase("mensaje")) {
                    enviarMensaje(escritor, lector, usuario);
                } else if (accion.equalsIgnoreCase("leer")) {
                    leerMensajes(escritor, usuario);
                } else if (accion.equalsIgnoreCase("eliminar")) {
                    eliminarMensajes(escritor, lector, usuario);
                } else if (accion.equalsIgnoreCase("bcuenta")) {
                    boolean borradoExitoso = borrarCuenta(escritor, lector, usuario);
                    if (borradoExitoso) {
                        usuario = "";
                        sesionActiva = false;
                    }
                } else {
                    escritor.println("Comando no reconocido.");
                }
            }
        }

        cliente.close();
        server.close();
    }


    private static void eliminarMensajes(PrintWriter escritor, BufferedReader lector, String usuario) throws IOException {
        escritor.println("¿Quieres eliminar mensajes Recibidos (R) o Enviados (E)?");
        String tipoEliminar = lector.readLine().trim();

        if (tipoEliminar.equalsIgnoreCase("R") || tipoEliminar.equalsIgnoreCase("E")) {
            escritor.println("Escribe el nombre del usuario:");
            String otroUsuario = lector.readLine().trim();

            if (!usuarioExiste(otroUsuario)) {
                escritor.println("El usuario " + otroUsuario + " no existe.");
                return;
            }

            boolean buscarRecibidos = tipoEliminar.equalsIgnoreCase("R");

            List<String> mensajesRelevantes = new ArrayList<>();
            List<String> todasLasLineas = new ArrayList<>();

            File archivo = new File(ARCHIVO_MENSAJES);
            if (!archivo.exists()) {
                escritor.println("Este chat está vacío.");
                return;
            }

            try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    todasLasLineas.add(linea);

                    String[] partes = linea.split(" -> ");
                    if (partes.length == 2) {
                        String remitente = partes[0].trim();
                        String resto = partes[1];
                        String destinatario = resto.substring(0, resto.indexOf(":")).trim();

                        boolean esMensajeRelevante = false;
                        if (buscarRecibidos) {
                            if (destinatario.equalsIgnoreCase(usuario) && remitente.equalsIgnoreCase(otroUsuario)) {
                                esMensajeRelevante = true;
                            }
                        } else {
                            if (remitente.equalsIgnoreCase(usuario) && destinatario.equalsIgnoreCase(otroUsuario)) {
                                esMensajeRelevante = true;
                            }
                        }

                        if (esMensajeRelevante) {
                            mensajesRelevantes.add(linea);
                        }
                    }
                }
            }

            if (mensajesRelevantes.isEmpty()) {
                escritor.println("Este chat está vacío.");
                return;
            }


            int paginaActual = 1;
            int totalPaginas = (int) Math.ceil((double) mensajesRelevantes.size() / MENSAJES_POR_PAGINA);

            while (true) {
                escritor.println("--- Pagina " + paginaActual + " de " + totalPaginas + " ---");
                int inicio = (paginaActual - 1) * MENSAJES_POR_PAGINA;
                int fin = Math.min(inicio + MENSAJES_POR_PAGINA, mensajesRelevantes.size());

                for (int i = inicio; i < fin; i++) {
                    String lineaMensaje = mensajesRelevantes.get(i);
                    String[] partes = lineaMensaje.split(" -> ");
                    String resto = partes[1];
                    String textoMensaje = resto.substring(resto.indexOf(":") + 1).trim();
                    String display;
                    if (tipoEliminar.equalsIgnoreCase("R")) {
                        display = "(" + (i + 1) + ") De " + partes[0].trim() + ": " + textoMensaje;
                    } else {
                        String destinatario = resto.substring(0, resto.indexOf(":")).trim();
                        display = "(" + (i + 1) + ") Para " + destinatario + ": " + textoMensaje;
                    }
                    escritor.println(display);
                }

                String opciones = "Opciones: 'eliminar <numero>' | 'cancelar'";
                if (paginaActual < totalPaginas) {
                    opciones += " | 'siguiente'";
                }
                if (paginaActual > 1) {
                    opciones += " | 'anterior'";
                }

                escritor.println(opciones);

                String accion = lector.readLine().trim();

                if (accion.equalsIgnoreCase("cancelar")) {
                    escritor.println("Eliminación cancelada.");
                    break;
                } else if (accion.equalsIgnoreCase("siguiente") && paginaActual < totalPaginas) {
                    paginaActual++;
                } else if (accion.equalsIgnoreCase("anterior") && paginaActual > 1) {
                    paginaActual--;
                } else if (accion.startsWith("eliminar ")) {
                    try {
                        String[] partesAccion = accion.split(" ");
                        int seleccion = Integer.parseInt(partesAccion[1]);

                        if (seleccion < 1 || seleccion > mensajesRelevantes.size()) {
                            escritor.println("Número de mensaje inválido. Intenta de nuevo.");
                        } else {
                            String mensajeAEliminar = mensajesRelevantes.get(seleccion - 1);
                            boolean eliminado = todasLasLineas.remove(mensajeAEliminar);
                            if (eliminado) {
                                reescribirMensajes(todasLasLineas);
                                escritor.println("Mensaje " + seleccion + " eliminado con éxito.");
                            } else {
                                escritor.println("Error interno: No se pudo encontrar el mensaje original para eliminar.");
                            }
                            break;
                        }
                    } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                        escritor.println("Entrada inválida. Usa el formato 'eliminar <numero>'.");
                    }
                } else {
                    escritor.println("Comando no reconocido. Vuelve a intentarlo.");
                }
            }

        } else {
            escritor.println("Opción inválida. Debes elegir 'R' (Recibidos) o 'E' (Enviados).");
        }
    }


    private static void reescribirMensajes(List<String> lineas) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_MENSAJES, false))) {
            for (String linea : lineas) {
                bw.write(linea);
                bw.newLine();
            }
        }
    }


    private static boolean borrarCuenta(PrintWriter escritor, BufferedReader lector, String usuario) throws IOException {
        escritor.println("Estás seguro de borrar tu usuario '" + usuario + "'? Esta acción es permanente (S/N).");
        String confirmacion = lector.readLine().trim();

        if (confirmacion.equalsIgnoreCase("S")) {

            if (eliminarUsuarioDeArchivo(usuario)) {
                escritor.println("Usuario '" + usuario + "' eliminado de cuentas.txt.");
            } else {
                escritor.println("Advertencia: No se encontró al usuario en cuentas.txt para eliminar.");
            }


            if (eliminarMensajesDeUsuario(usuario)) {
                escritor.println("Todos los mensajes asociados a '" + usuario + "' han sido eliminados de mensajes.txt.");
            } else {
                escritor.println("Advertencia: No había mensajes asociados a '" + usuario + "' para eliminar.");
            }

            escritor.println("Tu cuenta ha sido eliminada exitosamente. Sesión cerrada.");
            return true;
        } else {
            escritor.println("Borrado de cuenta cancelado. Volviendo al menú principal.");
            return false;
        }
    }


    private static boolean eliminarUsuarioDeArchivo(String usuario) throws IOException {
        File archivo = new File(ARCHIVO_USUARIOS);
        if (!archivo.exists()) return false;

        List<String> lineasRestantes = new ArrayList<>();
        boolean usuarioEncontrado = false;

        try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                String[] partes = linea.split(":");

                if (partes.length >= 1 && partes[0].trim().equalsIgnoreCase(usuario)) {
                    usuarioEncontrado = true;

                } else {
                    lineasRestantes.add(linea);
                }
            }
        }

        if (usuarioEncontrado) {

            try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_USUARIOS, false))) {
                for (String linea : lineasRestantes) {
                    bw.write(linea);
                    bw.newLine();
                }
            }
            return true;
        }
        return false;
    }


    private static boolean eliminarMensajesDeUsuario(String usuario) throws IOException {
        File archivo = new File(ARCHIVO_MENSAJES);
        if (!archivo.exists()) return false;

        List<String> lineasRestantes = new ArrayList<>();
        boolean mensajesEliminados = false;

        try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = br.readLine()) != null) {

                String[] partes = linea.split(" -> ");

                if (partes.length == 2) {
                    String remitente = partes[0].trim();
                    String resto = partes[1];
                    String destinatario = resto.substring(0, resto.indexOf(":")).trim();


                    if (remitente.equalsIgnoreCase(usuario) || destinatario.equalsIgnoreCase(usuario)) {
                        mensajesEliminados = true;

                    } else {
                        lineasRestantes.add(linea);
                    }
                } else {
                    lineasRestantes.add(linea);
                }
            }
        }

        if (mensajesEliminados) {

            reescribirMensajes(lineasRestantes);
        }
        return mensajesEliminados;
    }


    private static void leerMensajes(PrintWriter escritor, String usuario) throws IOException {
        File archivo = new File(ARCHIVO_MENSAJES);
        if (!archivo.exists()) {
            escritor.println("No hay mensajes para ti.");
            return;
        }

        List<String> mensajesParaUsuario = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                if (linea.contains(" -> " + usuario + ":")) {
                    mensajesParaUsuario.add(linea);
                }
            }
        }

        if (mensajesParaUsuario.isEmpty()) {
            escritor.println("No hay mensajes para ti.");
        } else {
            escritor.println("Mensajes para ti:");
            for (String mensaje : mensajesParaUsuario) {
                escritor.println(mensaje);
            }
        }
    }

    private static void enviarMensaje(PrintWriter escritor, BufferedReader lector, String remitente) throws IOException {
        escritor.println("Escribe el nombre del usuario al que le dejarás un mensaje:");
        String destinatario = lector.readLine().trim();

        if (usuarioExiste(destinatario)) {
            escritor.println("Escribe el mensaje:");
            String mensaje = lector.readLine();
            guardarMensaje(remitente, destinatario, mensaje);
            escritor.println("Mensaje enviado con éxito a " + destinatario + ".");
        } else {
            escritor.println("El usuario " + destinatario + " no existe.");
        }
    }

    private static void guardarMensaje(String remitente, String destinatario, String mensaje) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_MENSAJES, true))) {
            bw.write(remitente + " -> " + destinatario + ": " + mensaje);
            bw.newLine();
        }
    }

    private static void jugar(PrintWriter escritor, BufferedReader lector) throws IOException {
        Random random = new Random();
        boolean seguirJugando = true;

        while (seguirJugando) {
            int numeroSecreto = random.nextInt(10) + 1;
            int intentos = 0;
            boolean acertado = false;

            escritor.println("He elegido un número del 1 al 10. ¡Adivínalo!");

            while (intentos < 3 && !acertado) {
                escritor.println("Escribe tu intento #" + (intentos + 1) + ":");
                String entrada = lector.readLine();
                int numero;

                try {
                    numero = Integer.parseInt(entrada.trim());
                } catch (Exception e) {
                    escritor.println("Inválido: solo se permiten números.");
                    continue;
                }

                if (numero < 1 || numero > 10) {
                    escritor.println("Solo se permiten números entre 1 y 10.");
                    continue;
                }

                intentos++;

                if (numero == numeroSecreto) {
                    escritor.println("¡Felicidades, lo has logrado!");
                    acertado = true;
                } else if (intentos < 3) {
                    if (numero > numeroSecreto) {
                        escritor.println("No adivinaste, el número secreto es MENOR. Intenta de nuevo.");
                    } else {
                        escritor.println("No adivinaste, el número secreto es MAYOR. Intenta de nuevo.");
                    }
                }
            }

            if (!acertado) {
                escritor.println("Ni modo, no adivinaste. El número era " + numeroSecreto);
            }

            escritor.println("¿Quieres jugar otra vez? (si/no)");
            String respuesta = lector.readLine();
            if (respuesta == null || !respuesta.trim().equalsIgnoreCase("si")) {
                seguirJugando = false;
                escritor.println("Juego terminado.");
            }
        }
    }

    private static String obtenerListaUsuarios() throws IOException {
        StringBuilder lista = new StringBuilder();
        File archivo = new File(ARCHIVO_USUARIOS);
        if (!archivo.exists()) {
            return "No hay usuarios registrados.";
        }

        try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                String[] partes = linea.split(":");
                if (partes.length >= 1) {
                    lista.append(partes[0].trim()).append("\n");
                }
            }
        }
        return lista.toString();
    }

    private static boolean usuarioExiste(String usuario) throws IOException {
        File archivo = new File(ARCHIVO_USUARIOS);
        if (!archivo.exists()) return false;

        try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                String[] partes = linea.split(":");
                if (partes.length == 2 && partes[0].trim().equalsIgnoreCase(usuario.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void guardarUsuario(String usuario, String pin) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(ARCHIVO_USUARIOS, true))) {
            bw.write(usuario.trim() + ":" + pin.trim());
            bw.newLine();
        }
    }

    private static boolean validarUsuario(String usuario, String pin) throws IOException {
        File archivo = new File(ARCHIVO_USUARIOS);
        if (!archivo.exists()) return false;

        try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                String[] partes = linea.split(":");
                if (partes.length == 2) {
                    String nombreGuardado = partes[0].trim();
                    String pinGuardado = partes[1].trim();

                    if (usuario.trim().equalsIgnoreCase(nombreGuardado) && pin.trim().equals(pinGuardado)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}