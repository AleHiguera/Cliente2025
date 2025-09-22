import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class cliente2025 {
    public static void main(String[] args) throws IOException {
        Socket socket = new Socket("localhost", 8080);
        PrintWriter escritor = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader lector = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in));

        String mensajeServidor;
        while ((mensajeServidor = lector.readLine()) != null) {
            System.out.println("Servidor: " + mensajeServidor);

            if (mensajeServidor.equals("No hay mensajes para ti.") ||
                    mensajeServidor.contains("Usuarios registrados:") ||
                    mensajeServidor.contains("Opciones:") || // NUEVO: Para la paginación
                    mensajeServidor.contains("--- Pagina ")) { // NUEVO: Para la paginación
                // No se hace nada, el bucle continuará para mostrar la siguiente línea
            }
            if (mensajeServidor.contains("Escribe tu nombre de usuario:") ||
                    mensajeServidor.contains("Escribe tu PIN de 4 digitos:") ||
                    mensajeServidor.contains("¿Quieres registrarte") ||
                    mensajeServidor.contains("Escribe 'cerrar'") ||
                    mensajeServidor.contains("Escribe tu PIN de 4 digitos para firmar:") ||
                    mensajeServidor.contains("Escribe el nombre del usuario") ||
                    mensajeServidor.contains("Escribe el mensaje:") ||
                    mensajeServidor.contains("Escribe tu intento #") ||
                    mensajeServidor.contains("¿Quieres jugar otra vez?") ||
                    mensajeServidor.contains("¿Quieres eliminar mensajes") ||
                    mensajeServidor.contains("Escribe el número del mensaje") ||
                    mensajeServidor.contains("Estás seguro de borrar tu usuario") ||
                    mensajeServidor.contains("Opciones:")) {

                System.out.print("Tú: ");
                String entradaUsuario = teclado.readLine();
                escritor.println(entradaUsuario);

                if (entradaUsuario.equalsIgnoreCase("salir")) {
                    break;
                }
            }
        }
        socket.close();
    }
}