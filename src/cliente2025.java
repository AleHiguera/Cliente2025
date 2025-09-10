

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

                // El servidor espera respuesta en la mayoría de casos
                if (mensajeServidor.contains("registrarte") ||
                        mensajeServidor.contains("Escribe") ||
                        mensajeServidor.contains("opcion") ||
                        mensajeServidor.contains("intento") ||
                        mensajeServidor.contains("¿Quieres jugar otra vez?") ||
                        mensajeServidor.contains("Escribe 'cerrar'") ||
                        mensajeServidor.contains("Escribe 'jugar'") ||
                        mensajeServidor.contains("Bienvenido.") ||
                        mensajeServidor.contains("Juego terminado")) {

                    String entradaUsuario = teclado.readLine();
                    escritor.println(entradaUsuario);

                    // Si el usuario pone salir desde el cliente, también cortamos
                    if (entradaUsuario.equalsIgnoreCase("salir")) {
                        break;
                    }
                }

                if (mensajeServidor.contains("¡Adiós!")) {
                    break;
                }
            }

            socket.close();
        }
    }

