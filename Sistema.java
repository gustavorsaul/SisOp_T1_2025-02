import java.util.Scanner;

// Ponto de entrada principal da aplicação. Contém o shell do usuário.
public class Sistema {
    
    private Hardware.HW hw;
    private SisOp so;
    private Programs progs;
    private final int TAM_MEM = 1024;
    private final int QUANTUM = 4;

    public Sistema() {
        this.hw = new Hardware.HW(TAM_MEM, 16); 
        this.so = new SisOp(hw);
        this.progs = new Programs();
    }

    public void run() {
        System.out.println("Sistema Operacional iniciado em modo BLOQUEANTE.");
        System.out.println("Use 'execAll' para rodar processos ou 'thread2' para ativar o modo contínuo.");
        
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            String[] command = scanner.nextLine().trim().split("\\s+");
            if (command.length == 0 || command[0].isEmpty()) continue;

            switch (command[0].toLowerCase()) {
                case "new":
                    if (command.length > 1) 
                        so.processManager.criaProcesso(progs.retrieveProgram(command[1]));
                    else 
                        System.out.println("Uso: new <nomeDoPrograma>");
                    break;
                case "rm":
                    if (command.length > 1) 
                        try { 
                            so.processManager.desalocaProcesso(Integer.parseInt(command[1])); 
                        } catch (NumberFormatException e) { 
                            System.out.println("ID inválido."); 
                        }
                    else 
                        System.out.println("Uso: rm <id>");
                    break;
                case "ps":
                    so.processManager.listAllProcesses();
                    break;
                case "dump":
                    if (command.length > 1) 
                        try { 
                            so.processManager.dumpProcess(Integer.parseInt(command[1])); 
                        } catch (NumberFormatException e) { 
                            System.out.println("ID inválido."); 
                        }
                    else 
                        System.out.println("Uso: dump <id>");
                    break;
                case "dumpm":
                    if (command.length > 2) 
                        try { 
                            so.utils.dump(Integer.parseInt(command[1]), Integer.parseInt(command[2])); 
                        } catch (NumberFormatException e) { 
                            System.out.println("Endereços inválidos."); 
                        }
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
                    scanner.close();
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
    }

    public static void main(String args[]) {
        Sistema s = new Sistema();
        s.run();
    }

    // --- CLASSES INTERNAS DE SUPORTE À APLICAÇÃO ---

    public static class SchedulerExecutor implements Runnable {
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
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    System.out.println("Thread do escalonador interrompida. Encerrando.");
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

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
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 7, -1, 13),
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
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 5), // numero para colocar na memoria, ou pode ser lido
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 19),
                    new Hardware.Word(Hardware.CPU.Opcode.LDD, 0, -1, 19),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 6, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 7, -1, 13),
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIE, 7, 0, 0), // POS 9 pula pra STD (Stop-1)
                    new Hardware.Word(Hardware.CPU.Opcode.MULT, 1, 0, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.SUB, 0, 6, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.JMP, -1, -1, 9), // pula para o JMPIE
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 1, -1, 18),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 8, -1, 2), // escrita
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 9, -1, 18), // endereco com valor a escrever
                    new Hardware.Word(Hardware.CPU.Opcode.SYSCALL, -1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.STOP, -1, -1, -1), // POS 17
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // POS 18
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1) } // POS 19
                ),

                new Program("progMinimo", new Hardware.Word[] {
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 999),
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 8),
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 9),
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 10),
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 11),
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 12),
                    new Hardware.Word(Hardware.CPU.Opcode.STOP, -1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 7
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 8
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 9
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 10
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 11
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // 12
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1) // 13
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
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // POS 20
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
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // POS 20
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
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 8, -1, 1), // leitura
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 9, -1, 55),
                    new Hardware.Word(Hardware.CPU.Opcode.SYSCALL, -1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDD, 7, -1, 55),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 3, -1, 0),
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 3, 7, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 4, -1, 36), // posicao para qual ira pular (stop) *
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, -1), // caso negativo
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 1, -1, 41),
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIL, 4, 7, -1), // pula pra stop caso negativo *
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIE, 4, 7, -1), // pula pra stop caso 0
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 7, -1, 41),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, 0),
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 1, -1, 41),
                    new Hardware.Word(Hardware.CPU.Opcode.SUBI, 3, -1, 1), // se 1 pula pro stop
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIE, 4, 3, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 3, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 2, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 2, -1, 42),
                    new Hardware.Word(Hardware.CPU.Opcode.SUBI, 3, -1, 2), // se 2 pula pro stop
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIE, 4, 3, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 43),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 6, -1, 25), // salva posição de retorno do loop
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 5, -1, 0), // salva tamanho
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 5, 7, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 7, -1, 0), // zera (inicio do loop)
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 7, 5, -1), // recarrega tamanho
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 3, -1, 0),
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 3, 1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, 0),
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 1, 2, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 2, 3, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.STX, 0, 2, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 0, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.SUB, 7, 0, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIG, 6, 7, -1), // volta para o inicio do loop
                    new Hardware.Word(Hardware.CPU.Opcode.STOP, -1, -1, -1), // POS 36
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1), // POS 41
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
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 0, -1, 7), // numero para colocar na memoria
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 0, -1, 50),
                    new Hardware.Word(Hardware.CPU.Opcode.LDD, 0, -1, 50),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 1, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 6, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 7, -1, 13),
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIE, 7, 0, 0), // POS 9 pula pra STD (Stop-1)
                    new Hardware.Word(Hardware.CPU.Opcode.MULT, 1, 0, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.SUB, 0, 6, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.JMP, -1, -1, 9), // pula para o JMPIE
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 1, -1, 15),
                    new Hardware.Word(Hardware.CPU.Opcode.STOP, -1, -1, -1), // POS 14
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1) // POS 15
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
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 1
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 3, -1, 99),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 3, -1, 22), // Posicao para pulo CHAVE 2
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 3, -1, 98),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 3, -1, 38), // Posicao para pulo CHAVE 3
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 3, -1, 97),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 4 (não usada)
                    new Hardware.Word(Hardware.CPU.Opcode.STD, 3, -1, 96),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 6, -1, 0), // r6 = r7 - 1 POS 22
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 6, 7, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.SUBI, 6, -1, 1), // ate aqui
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIEM, -1, 6, 97),
                    new Hardware.Word(Hardware.CPU.Opcode.LDX, 0, 5, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDX, 1, 4, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 2, -1, 0),
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 2, 0, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.SUB, 2, 1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 4, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.SUBI, 6, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.JMPILM, -1, 2, 99), // LOOP chave 1 caso neg procura prox
                    new Hardware.Word(Hardware.CPU.Opcode.STX, 5, 1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.SUBI, 4, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.STX, 4, 0, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 4, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIGM, -1, 6, 99), // LOOP chave 1 POS 38
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 5, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.SUBI, 7, -1, 1),
                    new Hardware.Word(Hardware.CPU.Opcode.LDI, 4, -1, 0), // r4 = r5 + 1 POS 41
                    new Hardware.Word(Hardware.CPU.Opcode.ADD, 4, 5, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.ADDI, 4, -1, 1), // ate aqui
                    new Hardware.Word(Hardware.CPU.Opcode.JMPIGM, -1, 7, 98), // LOOP chave 2
                    new Hardware.Word(Hardware.CPU.Opcode.STOP, -1, -1, -1), // POS 45
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1),
                    new Hardware.Word(Hardware.CPU.Opcode.DATA, -1, -1, -1)
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