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

            // Verifica si el servidor está pidiendo una respuesta específica
            if (mensajeServidor.contains("Escribe tu nombre de usuario:") ||
                    mensajeServidor.contains("Escribe tu PIN de 4 digitos:") ||
                    mensajeServidor.contains("¿Quieres registrarte") ||
                    mensajeServidor.contains("Escribe 'cerrar'") ||
                    mensajeServidor.contains("Escribe tu PIN de 4 digitos para firmar:") ||
                    mensajeServidor.contains("Escribe el nombre del usuario") ||
                    mensajeServidor.contains("Escribe el mensaje:") ||
                    mensajeServidor.contains("No hay mensajes para ti.")) {

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