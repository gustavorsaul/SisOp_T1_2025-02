import java.util.Scanner;
// Importações de IO e Formatação movidas para o Logger em SisOp.java

// Ponto de entrada principal da aplicação. Contém o shell do usuário.
public class Sistema {
    
    private Hardware.HW hw;
    private SisOp so;
    private Programs progs;
    private final int TAM_MEM = 1024;
    private final int QUANTUM = 4;
    private Scanner mainScanner; 

    // --- Variáveis de Sincronização de E/S ---
    private final Object ioConsoleLock = new Object();
    private volatile boolean isWaitingForIO = false; 
    private volatile String ioInputBuffer = null;
    private int ioProcessId = 0;
    // --- Fim das Variáveis de Sincronização ---

    public Sistema() {
        this.mainScanner = new Scanner(System.in);
        this.hw = new Hardware.HW(TAM_MEM, 16); 
        this.so = new SisOp(hw, this); 
        this.progs = new Programs();
    }

    // Métodos para o DeviceManager (outra thread) usar
    public Object getIoConsoleLock() { return ioConsoleLock; }
    public void startWaitingForIO(int pcbId) {
        this.isWaitingForIO = true;
        this.ioProcessId = pcbId;
        this.ioInputBuffer = null;
    }
    public String getIoInputBuffer() { return this.ioInputBuffer; }

    public void run() {
        System.out.println("Sistema Operacional iniciado em modo BLOQUEANTE.");
        System.out.println("Use 'execAll' para rodar processos ou 'thread2' para ativar o modo contínuo.");
        
        while (true) {
            try {
                if (isWaitingForIO) {
                    System.out.print("\n>>> Dispositivo de E/S: Processo " + ioProcessId + " requisita um valor de entrada. Digite um número: ");
                    this.ioInputBuffer = mainScanner.nextLine();
                    this.isWaitingForIO = false;
                    synchronized (ioConsoleLock) {
                        ioConsoleLock.notifyAll();
                    }
                    Thread.sleep(50);
                } else {
                    System.out.print("> ");
                    String line = mainScanner.nextLine();
                    String[] command = line.trim().split("\\s+");
                    if (command.length == 0 || command[0].isEmpty()) continue;

                    switch (command[0].toLowerCase()) {
                        case "new":
                            if (command.length > 1) {
                                // --- MUDANÇA (LOGGER) ---
                                // Passa o nome do programa (command[1]) para o criador
                                so.processManager.criaProcesso(progs.retrieveProgram(command[1]), command[1]);
                            } else {
                                System.out.println("Uso: new <nomeDoPrograma>");
                            }
                            break;
                        case "rm":
                            if (command.length > 1) 
                                try { so.processManager.desalocaProcesso(Integer.parseInt(command[1])); } 
                                catch (NumberFormatException e) { System.out.println("ID inválido."); }
                            else 
                                System.out.println("Uso: rm <id>");
                            break;
                        case "ps":
                            so.processManager.listAllProcesses();
                            break;
                        case "dump":
                            if (command.length > 1) 
                                try { so.processManager.dumpProcess(Integer.parseInt(command[1])); } 
                                catch (NumberFormatException e) { System.out.println("ID inválido."); }
                            else 
                                System.out.println("Uso: dump <id>");
                            break;
                        case "dumpm":
                            if (command.length > 2) 
                                try { so.utils.dump(Integer.parseInt(command[1]), Integer.parseInt(command[2])); } 
                                catch (NumberFormatException e) { System.out.println("Endereços inválidos."); }
                            else 
                                System.out.println("Uso: dumpM <inicio> <fim>");
                            break;
                        case "traceon":
                            hw.cpu.setDebug(true); 
                            System.out.println("Modo trace ativado.");
                            break;
                        case "traceoff":
                            hw.cpu.setDebug(false); 
                            System.out.println("Modo trace desativado.");
                            break;
                        case "help":
                            System.out.println("Comandos: new <prog>, rm <id>, ps, dump <id>, dumpm <ini> <fim>, execall, thread2, traceon, traceoff, exit");
                            break;
                        case "exit":
                            // --- MUDANÇA (LOGGER) ---
                            // Fecha o arquivo de log antes de sair
                            so.logger.close();
                            mainScanner.close();
                            System.exit(0);
                            return;
                        case "execall":
                            so.processManager.execAllBlocking(QUANTUM);
                            break;
                        case "thread2":
                            so.activateThreadedMode(QUANTUM);
                            break;
                        default:
                            System.out.println("Comando desconhecido: " + command[0]);
                    }
                }
            } catch (Exception e) {
                System.out.println("Erro no shell: " + e.getMessage());
                e.printStackTrace();
                if (e instanceof java.util.NoSuchElementException) {
                    System.out.println("Encerrando sistema.");
                    System.exit(1);
                }
            }
        }
    }

    public static void main(String args[]) {
        Sistema s = new Sistema();
        s.run();
    }

    // --- CLASSES INTERNAS DE SUPORTE À APLICAÇÃO ---

    public static class SchedulerExecutor implements Runnable {
        // ... (Classe SchedulerExecutor inalterada) ...
        private SisOp so;
        private int quantum;

        public SchedulerExecutor(SisOp so, int quantum) {
            this.so = so;
            this.quantum = quantum;
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    synchronized (so.processManager.getSchedulerLock()) {
                        while (so.processManager.getReadyQueue().isEmpty() && so.processManager.getRunningProcess() == null) {
                            so.processManager.getSchedulerLock().wait();
                        }
                        if (so.processManager.getRunningProcess() == null && !so.processManager.getReadyQueue().isEmpty()) {
                            so.processManager.escalonar(false);
                        }
                    }
                    
                    if (so.processManager.getRunningProcess() != null) {
                        so.hw.cpu.step(this.quantum);
                    }
                    
                    Thread.sleep(100); // 100ms
                } catch (InterruptedException e) {
                    System.out.println("Thread do escalonador interrompida. Encerrando.");
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // Classe de Programas
    public static class Programs {
        public class Program {
            public String name;
            public Hardware.Word[] code;
            public Program(String n, Hardware.Word[] c) { this.name = n; this.code = c; }
        }

        public Program[] progs;

        public Programs() {
            this.progs = new Program[]{
                new Program("fatorial", new Hardware.Word[]{ 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 5), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, 1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 6, -1, 1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 7, -1, 8), 
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIE, 7, 0, 0), 
                    new Hardware.Word(Hardware.CPU.Opcode.MULT, 1, 0, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.SUB, 0, 6, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.JMP, -1, -1, 4), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 1, -1, 13), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 8, -1, 2), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 9, -1, 13), 
                    new Hardware.Word(Hardware.CPU.Opcode.SYSCALL,-1,-1,-1), 
                    new Hardware.Word(Hardware.CPU.Opcode.STOP, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1) 
                }),
                
                new Program("fatorialV2", new Hardware.Word[] { 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 5), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 19), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDD, 0, -1, 19), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 2, -1, 13), 
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIL, 2, 0, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, 1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 6, -1, 1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 7, -1, 13), 
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIE, 7, 0, 0), 
                    new Hardware.Word(Hardware.CPU.Opcode.MULT, 1, 0, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.SUB, 0, 6, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.JMP, -1, -1, 9), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 1, -1, 18), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 8, -1, 2), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 9, -1, 18), 
                    new Hardware.Word(Hardware.CPU.Opcode.SYSCALL, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.STOP, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1) 
                }),
                
                new Program("progMinimo", new Hardware.Word[] { 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 999), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 8), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 9), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 10), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 11), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 12), 
                    new Hardware.Word(Hardware.CPU.Opcode.STOP, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1) 
                }),
                
                new Program("fibonacci10", new Hardware.Word[] { 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, 0), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 1, -1, 20), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 2, -1, 1), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 2, -1, 21), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 22), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 6, -1, 6), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 7, -1, 31), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 3, -1, 0), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 3, 1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, 0), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 1, 2, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 2, 3, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.STX, 0, 2, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 0, -1, 1), 
                    new Hardware.Word(Hardware.CPU.Opcode.SUB, 7, 0, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIG, 6, 7, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.STOP, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1) 
                }),
                
                new Program("fibonacci10v2", new Hardware.Word[] { 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, 0), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 1, -1, 20), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 2, -1, 1), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 2, -1, 21), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 22), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 6, -1, 6), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 7, -1, 31), 
                    new Hardware.Word(Hardware.CPU.Opcode.MOVE, 3, 1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.MOVE, 1, 2, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 2, 3, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.STX, 0, 2, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 0, -1, 1), 
                    new Hardware.Word(Hardware.CPU.Opcode.SUB, 7, 0, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIG, 6, 7, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.STOP, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1) 
                }),
                
                new Program("fibonacciREAD", new Hardware.Word[] { 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 8, -1, 1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 9, -1, 55), 
                    new Hardware.Word(Hardware.CPU.Opcode.SYSCALL, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDD, 7, -1, 55), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 3, -1, 0), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 3, 7, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 4, -1, 36), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 1, -1, 41), 
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIL, 4, 7, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIE, 4, 7, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 7, -1, 42), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, 0), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 1, -1, 41), 
                    new Hardware.Word(Hardware.CPU.Opcode.SUBI, 3, -1, 1), 
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIE, 4, 3, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 3, -1, 1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 2, -1, 1), 
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 2, -1, 42), 
                    new Hardware.Word(Hardware.CPU.Opcode.SUBI, 3, -1, 2), 
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIE, 4, 3, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 43), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 6, -1, 25), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 5, -1, 0), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 5, 7, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 7, -1, 0), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 7, 5, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 3, -1, 0), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 3, 1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, 0), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 1, 2, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 2, 3, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.STX, 0, 2, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 0, -1, 1), 
                    new Hardware.Word(Hardware.CPU.Opcode.SUB, 7, 0, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIG, 6, 7, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.STOP, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), 
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1) 
                }),
                

                new Program("PB", new Hardware.Word[] { 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 7),     // 0: numero para colocar na memoria
                    
                    // --- CORREÇÃO ---
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 15),    // 1: Salva 7 em [15] (ANTES: 50)
                    new Hardware.Word(Hardware.CPU.Opcode.LDD, 0, -1, 15),    // 2: Carrega 7 de [15] (ANTES: 50)
                    // --- FIM CORREÇÃO ---

                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, -1),    // 3
                    
                    // --- CORREÇÃO ---
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 2, -1, 14),    // 4: SALVAR POS STOP (14) (ANTES: 13)
                    // --- FIM CORREÇÃO ---

                    new Hardware.Word(Hardware.CPU.Opcode.JMPIL, 2, 0, -1),   // 5: caso negativo pula pro STD
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, 1),     // 6
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 6, -1, 1),     // 7
                    
                    // --- CORREÇÃO ---
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 7, -1, 13),    // 8: SALVAR POS STD (13)
                    // --- FIM CORREÇÃO ---

                    new Hardware.Word(Hardware.CPU.Opcode.JMPIE, 7, 0, 0),    // 9: POS 9 pula pra STD (Stop-1)
                    new Hardware.Word(Hardware.CPU.Opcode.MULT, 1, 0, -1),   // 10
                    new Hardware.Word(Hardware.CPU.Opcode.SUB, 0, 6, -1),    // 11
                    new Hardware.Word(Hardware.CPU.Opcode.JMP, -1, -1, 9),    // 12: pula para o JMPIE
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 1, -1, 15),    // 13: Salva resultado em [15]
                    new Hardware.Word(Hardware.CPU.Opcode.STOP, -1, -1, -1), // 14: POS 14
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1)  // 15: POS 15
                }),
                
                new Program("PC", new Hardware.Word[] { 
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 7, -1, 5), // TAMANHO DO BUBBLE SORT (N)
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 6, -1, 5), // aux N
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 5, -1, 46), // LOCAL DA MEMORIA
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 4, -1, 47), // aux local memoria
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 4), // colocando valores na memoria
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 46),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 3),
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 47),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 5),
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 48),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 49),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 2),
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 50), // colocando valores na memoria até aqui - POS 13
                    
                    // --- CORREÇÃO ---
                    // Endereços de pulo agora salvos em 60-63 (dentro da Página 3)
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 1
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 3, -1, 60), // ANTES: 99
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 3, -1, 22), // Posicao para pulo CHAVE 2
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 3, -1, 61), // ANTES: 98
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 3, -1, 38), // Posicao para pulo CHAVE 3
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 3, -1, 62), // ANTES: 97
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 4 (não usada)
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 3, -1, 63), // ANTES: 96
                    // --- FIM CORREÇÃO ---

                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 6, -1, 0), // r6 = r7 - 1 POS 22
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 6, 7, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.SUBI, 6, -1, 1), // ate aqui
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIEM, -1, 6, 62), // ANTES: 97
                    new Hardware.Word(Hardware.CPU.Opcode.LDX, 0, 5, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDX, 1, 4, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 2, -1, 0),
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 2, 0, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.SUB, 2, 1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 4, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.SUBI, 6, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.JMPILM, -1, 2, 60), // ANTES: 99
                    new Hardware.Word(Hardware.CPU.Opcode.STX, 5, 1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.SUBI, 4, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.STX, 4, 0, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 4, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIGM, -1, 6, 60), // ANTES: 99
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 5, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.SUBI, 7, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 4, -1, 0), // r4 = r5 + 1 POS 41
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 4, 5, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 4, -1, 1), // ate aqui
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIGM, -1, 7, 61), // ANTES: 98
                    new Hardware.Word(Hardware.CPU.Opcode.STOP, -1, -1, -1), // POS 45
                    
                    // O restante são os dados e espaço livre (até o 63)
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 46
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 47
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 48
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 49
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 50
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 51
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 52
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 53
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 54
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 55
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 56
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 57
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 58
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 59
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 60 (usado para pulo)
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 61 (usado para pulo)
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 62 (usado para pulo)
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1)  // 63 (usado para pulo)
                })
            };
        }

        public Hardware.Word[] retrieveProgram(String pname) {
            for (Program p : progs) {
                if (p != null && p.name.equals(pname)) {
                    return p.code;
                }
            }
            return null;
        }
    }
}