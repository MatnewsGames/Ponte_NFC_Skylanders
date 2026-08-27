using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;

namespace SkylanderAutoIndexBridge
{
    internal class Program
    {
        private const int PORTA_CELULAR = 8888;
        private static readonly string DUMPS_DIR = @"C:\virtport\dumps";

        private static readonly Dictionary<uint, string> BibliotecaSkylanders = new Dictionary<uint, string>();
        private static uint currentActiveToyId = 0xFFFFFFFF;

        // CancellationToken para gerenciar o Debounce de ejeção
        private static CancellationTokenSource ctsEjecaoPending = null;

        [DllImport("user32.dll")]
        private static extern bool SetForegroundWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern IntPtr FindWindow(string lpClassName, string lpWindowName);

        [DllImport("user32.dll")]
        private static extern IntPtr FindWindowEx(IntPtr parentHandle, IntPtr childAfter, string lpszClass, string lpszWindow);

        [DllImport("user32.dll", CharSet = CharSet.Auto)]
        private static extern IntPtr SendMessage(IntPtr hWnd, uint Msg, IntPtr wParam, string lParam);

        [DllImport("user32.dll", CharSet = CharSet.Auto)]
        private static extern IntPtr SendMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, int dwExtraInfo);

        private const uint WM_SETTEXT = 0x000C;
        private const uint BM_CLICK = 0x00F5;
        private const byte VK_TAB = 0x09;
        private const byte VK_RETURN = 0x0D;
        private const byte VK_SPACE = 0x20;
        private const uint KEYEVENTF_KEYUP = 0x0002;

        static async Task Main(string[] args)
        {
            Console.Title = "Ponte NFC -> VirtPort Stable Engine";

            GarantirDiretorio(DUMPS_DIR);

            Console.ForegroundColor = ConsoleColor.Cyan;
            Console.WriteLine("==================================================");
            Console.WriteLine(" [PONTE NFC -> VIRTPORT STABLE ENGINE]");
            Console.WriteLine("==================================================");
            Console.ResetColor();

            IndexarPastaDumps();

            TcpListener server = new TcpListener(IPAddress.Any, PORTA_CELULAR);
            server.Start();

            Console.WriteLine($"\n[+] Escutando celular na porta TCP: {PORTA_CELULAR}...\n");

            while (true)
            {
                try
                {
                    using (TcpClient tcpClient = await server.AcceptTcpClientAsync())
                    {
                        IPEndPoint remoteIp = (IPEndPoint)tcpClient.Client.RemoteEndPoint;
                        Console.ForegroundColor = ConsoleColor.Green;
                        Console.WriteLine($"[OK] Celular conectado: {remoteIp.Address}");
                        Console.ResetColor();

                        using (NetworkStream stream = tcpClient.GetStream())
                        {
                            byte[] buffer = new byte[1024];

                            while (tcpClient.Connected)
                            {
                                int bytesRead = 0;
                                try
                                {
                                    bytesRead = await stream.ReadAsync(buffer, 0, buffer.Length);
                                    if (bytesRead == 0) break;
                                }
                                catch (Exception)
                                {
                                    // Conexão do celular caiu de forma abrupta
                                    break;
                                }

                                if (EhPacoteVazio(buffer, bytesRead))
                                {
                                    AgendarEjecaoComDebounce();
                                }
                                else if (bytesRead >= 20)
                                {
                                    // Cancela qualquer ejeção agendada pois a tag continua no leitor
                                    CancelarEjecaoPendente();

                                    ushort baseToyId = (ushort)(buffer[16] | (buffer[17] << 8));
                                    ushort variantId = (ushort)(buffer[18] | (buffer[19] << 8));
                                    uint fullToyId = (uint)(baseToyId | (variantId << 16));

                                    if (currentActiveToyId != fullToyId)
                                    {
                                        currentActiveToyId = fullToyId;

                                        Console.ForegroundColor = ConsoleColor.Green;
                                        Console.WriteLine($"\n[+] TAG NFC ESTÁVEL -> Base ID: {baseToyId} | Variante: {variantId}");
                                        Console.ResetColor();

                                        string caminhoArquivo = BuscarMelhorCorrespondencia(fullToyId, baseToyId);

                                        if (caminhoArquivo != null)
                                        {
                                            string nomeArquivo = Path.GetFileName(caminhoArquivo);
                                            Console.ForegroundColor = ConsoleColor.Cyan;
                                            Console.WriteLine($"[*] Injetando no VirtPort: {nomeArquivo}");
                                            Console.ResetColor();

                                            await InjetarSkylanderUnico(caminhoArquivo);
                                        }
                                        else
                                        {
                                            Console.ForegroundColor = ConsoleColor.Red;
                                            Console.WriteLine($"[!] NENHUM ARQUIVO ENCONTRADO para o Base ID {baseToyId}!");
                                            Console.ResetColor();
                                        }
                                    }
                                }

                                Array.Clear(buffer, 0, buffer.Length);
                            }
                        }

                        Console.ForegroundColor = ConsoleColor.Yellow;
                        Console.WriteLine("[-] Celular desconectado. Aguardando reconexão...\n");
                        Console.ResetColor();
                    }
                }
                catch (Exception ex)
                {
                    Console.ForegroundColor = ConsoleColor.DarkGray;
                    Console.WriteLine($"[LOG] Reconectando socket: {ex.Message}");
                    Console.ResetColor();
                }
            }
        }

        private static void AgendarEjecaoComDebounce()
        {
            if (currentActiveToyId == 0xFFFFFFFF) return;
            if (ctsEjecaoPending != null) return; // Já existe um timer de ejeção rodando

            ctsEjecaoPending = new CancellationTokenSource();
            var token = ctsEjecaoPending.Token;

            Task.Run(async () =>
            {
                try
                {
                    // Espera 350ms antes de confirmar que a tag realmente saiu
                    await Task.Delay(350, token);

                    if (!token.IsCancellationRequested && currentActiveToyId != 0xFFFFFFFF)
                    {
                        Console.ForegroundColor = ConsoleColor.Yellow;
                        Console.WriteLine($"[-] TAG REMOVIDA CONFIRMADA -> Ejetando do VirtPort...");
                        Console.ResetColor();

                        await SimularEjecao();
                        currentActiveToyId = 0xFFFFFFFF;
                    }
                }
                catch (TaskCanceledException) { }
                finally
                {
                    ctsEjecaoPending = null;
                }
            });
        }

        private static void CancelarEjecaoPendente()
        {
            if (ctsEjecaoPending != null)
            {
                ctsEjecaoPending.Cancel();
                ctsEjecaoPending = null;
            }
        }

        private static async Task InjetarSkylanderUnico(string filePath)
        {
            IntPtr cemuHwnd = ObterJanelaCemu();
            if (cemuHwnd == IntPtr.Zero) return;

            SetForegroundWindow(cemuHwnd);
            await Task.Delay(100);

            PressionarTecla(VK_TAB);
            await Task.Delay(60);
            PressionarTecla(VK_SPACE);

            IntPtr openDialogHwnd = IntPtr.Zero;
            for (int i = 0; i < 15; i++)
            {
                await Task.Delay(50);
                openDialogHwnd = FindWindow("#32770", null);
                if (openDialogHwnd != IntPtr.Zero) break;
            }

            if (openDialogHwnd != IntPtr.Zero)
            {
                await Task.Delay(100);
                ForcarConfirmacaoNoDialogo(openDialogHwnd, filePath);
            }
        }

        private static void ForcarConfirmacaoNoDialogo(IntPtr openDialogHwnd, string filePath)
        {
            if (openDialogHwnd == IntPtr.Zero) return;

            SetForegroundWindow(openDialogHwnd);

            IntPtr editHwnd = FindWindowEx(openDialogHwnd, IntPtr.Zero, "Edit", null);
            if (editHwnd == IntPtr.Zero)
            {
                IntPtr comboEx = FindWindowEx(openDialogHwnd, IntPtr.Zero, "ComboBoxEx32", null);
                if (comboEx != IntPtr.Zero)
                {
                    IntPtr combo = FindWindowEx(comboEx, IntPtr.Zero, "ComboBox", null);
                    if (combo != IntPtr.Zero) editHwnd = FindWindowEx(combo, IntPtr.Zero, "Edit", null);
                }
            }

            if (editHwnd != IntPtr.Zero)
            {
                SendMessage(editHwnd, WM_SETTEXT, IntPtr.Zero, filePath);

                IntPtr btnOpen = FindWindowEx(openDialogHwnd, IntPtr.Zero, "Button", "&Open");
                if (btnOpen == IntPtr.Zero) btnOpen = FindWindowEx(openDialogHwnd, IntPtr.Zero, "Button", "&Abrir");
                if (btnOpen == IntPtr.Zero) btnOpen = FindWindowEx(openDialogHwnd, IntPtr.Zero, "Button", "Open");
                if (btnOpen == IntPtr.Zero) btnOpen = FindWindowEx(openDialogHwnd, IntPtr.Zero, "Button", "Abrir");

                if (btnOpen != IntPtr.Zero)
                {
                    SendMessage(btnOpen, BM_CLICK, IntPtr.Zero, IntPtr.Zero);
                }
                else
                {
                    PressionarTecla(VK_RETURN);
                }

                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine("[OK] Skylander selecionado com sucesso!");
                Console.ResetColor();
            }
        }

        private static async Task SimularEjecao()
        {
            IntPtr cemuHwnd = ObterJanelaCemu();
            if (cemuHwnd == IntPtr.Zero) return;

            SetForegroundWindow(cemuHwnd);
            await Task.Delay(100);

            PressionarTecla(VK_TAB);
            await Task.Delay(60);
            PressionarTecla(VK_TAB);
            await Task.Delay(60);
            PressionarTecla(VK_SPACE);
        }

        private static bool EhNomeLimpo(string filePath)
        {
            string fileName = Path.GetFileNameWithoutExtension(filePath).ToLower();
            string[] palavrasVariantes = new string[] {
                "dark", "mega", "legendary", "glow", "nitro", "jade", "gold",
                "enchanted", "series", "s2", "s3", "s4", "e3", "big bang", "bang",
                "heavy metal", "punch", "power", "lightcore", "supercharger"
            };

            foreach (string palavra in palavrasVariantes)
            {
                if (fileName.Contains(palavra)) return false;
            }
            return true;
        }

        private static void IndexarPastaDumps()
        {
            Console.WriteLine($"[*] Escaneando e indexando arquivos em: {DUMPS_DIR}");
            BibliotecaSkylanders.Clear();

            string[] arquivos = Directory.GetFiles(DUMPS_DIR, "*.sky");
            int mapeados = 0;

            foreach (string file in arquivos)
            {
                try
                {
                    byte[] header = new byte[32];
                    using (FileStream fs = File.OpenRead(file))
                    {
                        if (fs.Length < 32) continue;
                        fs.Read(header, 0, 32);
                    }

                    ushort baseToyId = (ushort)(header[16] | (header[17] << 8));
                    ushort variantId = (ushort)(header[18] | (header[19] << 8));
                    uint fullToyId = (uint)(baseToyId | (variantId << 16));

                    if (!BibliotecaSkylanders.ContainsKey(fullToyId))
                    {
                        BibliotecaSkylanders.Add(fullToyId, file);
                        mapeados++;
                    }
                    else
                    {
                        string arquivoExistente = BibliotecaSkylanders[fullToyId];
                        bool atualEhLimpo = EhNomeLimpo(file);
                        bool existenteEhLimpo = EhNomeLimpo(arquivoExistente);

                        if ((atualEhLimpo && !existenteEhLimpo) ||
                            (atualEhLimpo == existenteEhLimpo && Path.GetFileName(file).Length < Path.GetFileName(arquivoExistente).Length))
                        {
                            BibliotecaSkylanders[fullToyId] = file;
                        }
                    }
                }
                catch { }
            }

            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine($"[OK] {mapeados} Skylanders indexados!");
            Console.ResetColor();
        }

        private static string BuscarMelhorCorrespondencia(uint fullToyId, ushort baseToyId)
        {
            if (BibliotecaSkylanders.TryGetValue(fullToyId, out string caminhoExato))
            {
                return caminhoExato;
            }

            foreach (var item in BibliotecaSkylanders)
            {
                ushort keyBaseId = (ushort)(item.Key & 0xFFFF);
                if (keyBaseId == baseToyId)
                {
                    return item.Value;
                }
            }

            return null;
        }

        private static IntPtr ObterJanelaCemu()
        {
            Process[] procs = Process.GetProcessesByName("cemu");
            if (procs.Length > 0 && procs[0].MainWindowHandle != IntPtr.Zero)
            {
                return procs[0].MainWindowHandle;
            }
            return IntPtr.Zero;
        }

        private static void PressionarTecla(byte vkCode)
        {
            keybd_event(vkCode, 0, 0, 0);
            keybd_event(vkCode, 0, KEYEVENTF_KEYUP, 0);
        }

        private static void GarantirDiretorio(string dirPath)
        {
            if (!Directory.Exists(dirPath)) Directory.CreateDirectory(dirPath);
        }

        private static bool EhPacoteVazio(byte[] data, int length)
        {
            for (int i = 0; i < length; i++)
            {
                if (data[i] != 0) return false;
            }
            return true;
        }
    }
}