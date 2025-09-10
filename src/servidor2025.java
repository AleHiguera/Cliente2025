import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

public class servidor2025 {
    private static final String ARCHIVO_USUARIOS = "C://Users//al443//OneDrive/Escritorio//cuentas.txt";

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

                opcion = opcion.trim(); // limpia espacios
                if (opcion.equalsIgnoreCase("salir")) {
                    escritor.println("Cerrando servidor. ¡Adiós!");
                    break;
                }

                if (opcion.equalsIgnoreCase("R")) {
                    escritor.println("Escribe tu nombre de usuario:");
                    usuario = lector.readLine().trim();

                    if (usuarioExiste(usuario)) {
                        escritor.println("El usuario ya existe. Intenta firmar.");
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
                // El usuario está dentro → puede jugar o cerrar sesión
                escritor.println("Escribe 'cerrar' para cerrar sesión o 'jugar' para comenzar el juego.");
                String accion = lector.readLine();

                if (accion == null) {
                    escritor.println("Conexión cerrada.");
                    break;
                }

                accion = accion.trim(); // limpia entrada
                if (accion.equalsIgnoreCase("cerrar")) {
                    escritor.println("Sesión cerrada de " + usuario + ".");
                    usuario = "";
                    sesionActiva = false;
                } else if (accion.equalsIgnoreCase("jugar")) {
                    jugar(escritor, lector);
                } else {
                    escritor.println("Comando no reconocido.");
                }
            }
        }

        cliente.close();
        server.close();
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
                    escritor.println("Incorrecto. Intenta de nuevo.");
                }
            }

            if (!acertado) {
                escritor.println("Ni modo, no adivinaste. El número era " + numeroSecreto);
            }

            escritor.println("¿Quieres jugar otra vez? (si/no)");
            String respuesta = lector.readLine();
            if (respuesta == null || !respuesta.trim().equalsIgnoreCase("si")) {
                seguirJugando = false;
                escritor.println("Juego terminado. Escribe 'cerrar' para salir de tu sesión.");
            }
        }
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
