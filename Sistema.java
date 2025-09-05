// PUCRS - Escola Politécnica - Sistemas Operacionais
// Prof. Fernando Dotti
// Versão Final com Dupla Modalidade de Execução (Bloqueante e Contínua/Threaded)

import java.util.*;

public class Sistema {

    // ======================= PARÂMETROS DO SISTEMA =======================
    private static final int TAM_PG = 8;
    private static final int QUANTUM = 4;

    // ======================= H A R D W A R E =============================
    public class Memory {
        public Word[] pos;

        public Memory(int size) {
            pos = new Word[size];
            for (int i = 0; i < pos.length; i++) {
                pos[i] = new Word(Opcode.___, -1, -1, -1);
            }
        }
    }

    public class Word {
        public Opcode opc;
        public int r1;
        public int r2;
        public int p;

        public Word(Opcode _opc, int _r1, int _r2, int _p) {
            opc = _opc; r1 = _r1; r2 = _r2; p = _p;
        }
    }

    // ======================= C P U =======================================
    public enum Opcode {
        DATA, ___, JMP, JMPI, JMPIG, JMPIL, JMPIE, JMPIM, JMPIGM, JMPILM, JMPIEM,
        JMPIGK, JMPILK, JMPIEK, JMPIGT, ADDI, SUBI, ADD, SUB, MULT,
        LDI, LDD, STD, LDX, STX, MOVE, SYSCALL, STOP
    }

    public enum Interrupts {
        noInterrupt, intEnderecoInvalido, intInstrucaoInvalida, intOverflow, intQuantumEnd
    }

    public class CPU {
        private int maxInt, minInt;
        private int pc;
        private Word ir;
        private int[] reg;
        private Interrupts irpt;
        private Word[] m;
        private int tamPg = TAM_PG;
        private int[] tabelaPaginas = null;
        private InterruptHandling ih;
        private SysCallHandling sysCall;
        private boolean cpuStop;
        private boolean debug;
        private Utilities u;
        private int instructionCounter;

        public CPU(Memory _mem, boolean _debug) {
            maxInt = 32767; minInt = -32767;
            m = _mem.pos; reg = new int[10]; debug = _debug;
            instructionCounter = 0; irpt = Interrupts.noInterrupt;
        }

        public void setDebug(boolean _debug) { this.debug = _debug; }
        public void setAddressOfHandlers(InterruptHandling _ih, SysCallHandling _sysCall) { ih = _ih; sysCall = _sysCall; }
        public void setUtilities(Utilities _u) { u = _u; }
        public void setMMU(int[] _tabelaPaginas, int _tamPg) { this.tabelaPaginas = _tabelaPaginas; this.tamPg = _tamPg; }
        public int getContextPC() { return pc; }
        public int[] getContextRegs() { return Arrays.copyOf(reg, reg.length); }
        public void setContext(int _pc, int[] _regs) { pc = _pc; reg = Arrays.copyOf(_regs, _regs.length); }
        public void resetInstructionCounter() { instructionCounter = 0; }
        
        public int toPhysical(int endLogico) {
            if (tabelaPaginas == null) return endLogico;
            if (endLogico < 0) { irpt = Interrupts.intEnderecoInvalido; return -1; }
            int pag = endLogico / tamPg; int off = endLogico % tamPg;
            if (pag < 0 || pag >= tabelaPaginas.length) { irpt = Interrupts.intEnderecoInvalido; return -1; }
            int frame = tabelaPaginas[pag];
            if (frame < 0) { irpt = Interrupts.intEnderecoInvalido; return -1; }
            int endFis = frame * tamPg + off;
            if (endFis < 0 || endFis >= m.length) { irpt = Interrupts.intEnderecoInvalido; return -1; }
            return endFis;
        }

        private boolean legalFisico(int e) {
            if (e >= 0 && e < m.length) return true;
            else { irpt = Interrupts.intEnderecoInvalido; return false; }
        }

        public void step() {
            if (cpuStop) return;
            // FETCH
            int pcFis = toPhysical(pc);
            if (legalFisico(pcFis)) {
                ir = m[pcFis];
                if (debug) {
                    System.out.print("                                              regs: ");
                    for (int i = 0; i < 10; i++) System.out.print(" r[" + i + "]:" + reg[i]);
                    System.out.println();
                    System.out.print("                      pc(log) " + pc + " -> pc(fis) " + pcFis + "       exec: ");
                    u.dump(ir);
                }
                // EXEC
                switch (ir.opc) {
                    case LDI: reg[ir.r1] = ir.p; pc++; break;
                    case LDD: { int a = toPhysical(ir.p); if (legalFisico(a)) { reg[ir.r1] = m[a].p; pc++; } } break;
                    case LDX: { int a = toPhysical(reg[ir.r2]); if (legalFisico(a)) { reg[ir.r1] = m[a].p; pc++; } } break;
                    case STD: { int a = toPhysical(ir.p); if (legalFisico(a)) { m[a].opc = Opcode.DATA; m[a].p = reg[ir.r1]; pc++; } } break;
                    case STX: { int a = toPhysical(reg[ir.r1]); if (legalFisico(a)) { m[a].opc = Opcode.DATA; m[a].p = reg[ir.r2]; pc++; } } break;
                    case MOVE: reg[ir.r1] = reg[ir.r2]; pc++; break;
                    case ADD: reg[ir.r1] = reg[ir.r1] + reg[ir.r2]; testOverflow(reg[ir.r1]); pc++; break;
                    case ADDI: reg[ir.r1] = reg[ir.r1] + ir.p; testOverflow(reg[ir.r1]); pc++; break;
                    case SUB: reg[ir.r1] = reg[ir.r1] - reg[ir.r2]; testOverflow(reg[ir.r1]); pc++; break;
                    case SUBI: reg[ir.r1] = reg[ir.r1] - ir.p; testOverflow(reg[ir.r1]); pc++; break;
                    case MULT: reg[ir.r1] = reg[ir.r1] * reg[ir.r2]; testOverflow(reg[ir.r1]); pc++; break;
                    case JMP: pc = ir.p; break;
                    case JMPI: pc = reg[ir.r1]; break;
                    case JMPIG: pc = (reg[ir.r2] > 0) ? reg[ir.r1] : pc + 1; break;
                    case JMPIL: pc = (reg[ir.r2] < 0) ? reg[ir.r1] : pc + 1; break;
                    case JMPIE: pc = (reg[ir.r2] == 0) ? reg[ir.r1] : pc + 1; break;
                    case JMPIGK: pc = (reg[ir.r2] > 0) ? ir.p : pc + 1; break;
                    case JMPILK: pc = (reg[ir.r2] < 0) ? ir.p : pc + 1; break;
                    case JMPIEK: pc = (reg[ir.r2] == 0) ? ir.p : pc + 1; break;
                    case JMPIM: { int a = toPhysical(ir.p); if (legalFisico(a)) pc = m[a].p; } break;
                    case JMPIGM: { int a = toPhysical(ir.p); if (legalFisico(a)) pc = (reg[ir.r2] > 0) ? m[a].p : pc + 1; } break;
                    case JMPILM: { int a = toPhysical(ir.p); if (legalFisico(a)) pc = (reg[ir.r2] < 0) ? m[a].p : pc + 1; } break;
                    case JMPIEM: { int a = toPhysical(ir.p); if (legalFisico(a)) pc = (reg[ir.r2] == 0) ? m[a].p : pc + 1; } break;
                    case DATA: irpt = Interrupts.intInstrucaoInvalida; break;
                    case SYSCALL: sysCall.handle(); pc++; break;
                    case STOP: sysCall.stop(); break;
                    default: irpt = Interrupts.intInstrucaoInvalida; break;
                }
            }
            if (irpt == Interrupts.noInterrupt) {
                instructionCounter++;
                if (instructionCounter >= QUANTUM) { irpt = Interrupts.intQuantumEnd; }
            }
            if (irpt != Interrupts.noInterrupt) {
                ih.handle(irpt);
                irpt = Interrupts.noInterrupt;
            }
        }
        public void stop() { this.cpuStop = true; }
        public void start() { this.cpuStop = false; }
        private boolean testOverflow(int v) { if ((v < minInt) || (v > maxInt)) { irpt = Interrupts.intOverflow; return false; } return true; }
    }

    public class HW {
        public Memory mem; public CPU cpu;
        public HW(int tamMem) { mem = new Memory(tamMem); cpu = new CPU(mem, false); }
    }

    public class InterruptHandling {
        private SO so;
        public InterruptHandling(SO _so) { this.so = _so; }
        public void handle(Interrupts irpt) {
            switch (irpt) {
                case intQuantumEnd:
                    System.out.println("                                               Interrupcao de TEMPO");
                    so.gp.escalonar(false);
                    break;
                case intEnderecoInvalido:
                case intInstrucaoInvalida:
                case intOverflow:
                    System.out.println("                                               ERRO: Interrupcao " + irpt);
                    so.gp.terminaProcessoAtual();
                    break;
                default:
                     System.out.println("                                               Interrupcao " + irpt);
            }
        }
    }

    public class SysCallHandling {
        private SO so;
        public SysCallHandling(SO _so) { this.so = _so; }
        public void stop() { so.gp.terminaProcessoAtual(); }
        public void handle() {
            if (so.hw.cpu.reg[8] == 2) {
                System.out.print("SYSCALL - Print do Processo " + so.gp.runningProcess.getId() + ": ");
                int fis = so.hw.cpu.toPhysical(so.hw.cpu.reg[9]);
                if (fis >= 0) System.out.println("OUT: " + so.hw.mem.pos[fis].p);
            }
        }
    }

    public interface GM { int[] aloca(int nroPalavras); void desaloca(int[] tabelaPaginas); }

    public class SimplePagingGM implements GM {
        private int tamPg, qtdFrames; private boolean[] livre;
        public SimplePagingGM(int tamMem, int tamPg) { this.tamPg = tamPg; qtdFrames = tamMem / tamPg; livre = new boolean[qtdFrames]; Arrays.fill(livre, true); }
        public int[] aloca(int nroPalavras) {
            int paginasNec = (nroPalavras + tamPg - 1) / tamPg; List<Integer> frames = new ArrayList<>();
            for (int f = 0; f < qtdFrames && frames.size() < paginasNec; f++) if (livre[f]) frames.add(f);
            if (frames.size() < paginasNec) return null;
            int[] tabela = new int[paginasNec];
            for (int i = 0; i < paginasNec; i++) { int f = frames.get(i); tabela[i] = f; livre[f] = false; }
            return tabela;
        }
        public void desaloca(int[] tabelaPaginas) { for (int f : tabelaPaginas) if (f >= 0 && f < livre.length) livre[f] = true; }
    }

    public class Utilities {
        private HW hw;
        public Utilities(HW _hw) { this.hw = _hw; }
        public void loadProgramPaged(Word[] prog, int[] tabelaPaginas) {
            Word[] m = hw.mem.pos;
            for (int i = 0; i < prog.length; i++) {
                int pag = i / TAM_PG; int off = i % TAM_PG; int frame = tabelaPaginas[pag];
                int fis = frame * TAM_PG + off;
                m[fis] = new Word(prog[i].opc, prog[i].r1, prog[i].r2, prog[i].p);
            }
        }
        public void dump(Word w) { System.out.print("[ " + w.opc + ", " + w.r1 + ", " + w.r2 + ", " + w.p + " ] "); }
        public void dump(int ini, int fim) { for (int i = ini; i < fim; i++) { System.out.print(i + ":  "); dump(hw.mem.pos[i]); System.out.println(); } }
    }

    public class SO {
        public HW hw; public InterruptHandling ih; public SysCallHandling sc; public Utilities utils; public GM gm; public ProcessManager gp;
        public enum ExecutionMode { BLOCKING, THREADED }
        private ExecutionMode mode = ExecutionMode.BLOCKING;
        private boolean schedulerThreadStarted = false;

        public SO(HW _hw) {
            hw = _hw; ih = new InterruptHandling(this); sc = new SysCallHandling(this);
            hw.cpu.setAddressOfHandlers(ih, sc);
            gm = new SimplePagingGM(hw.mem.pos.length, TAM_PG);
            utils = new Utilities(hw); hw.cpu.setUtilities(utils);
            gp = new ProcessManager();
        }

        public void activateThreadedMode() {
            if (schedulerThreadStarted) {
                System.out.println("O modo de execução com threads já está ativo.");
                return;
            }
            if (gp.getRunningProcess() != null) {
                System.out.println("Não é possível mudar de modo enquanto um processo está em execução. Aguarde o fim do 'execAll'.");
                return;
            }
            this.mode = ExecutionMode.THREADED;
            this.schedulerThreadStarted = true;
            Thread schedulerThread = new Thread(new SchedulerExecutor(this));
            schedulerThread.start();
            System.out.println("Modo de execução contínuo (threaded) ativado.");
        }

        public enum ProcessState { READY, RUNNING, TERMINATED, BLOCKED }

        public class PCB {
            private int id, pc; private int[] pageTable, registradores; private ProcessState state;
            public PCB(int id, int[] pageTable) {
                this.id = id; this.pc = 0; this.pageTable = pageTable; this.state = ProcessState.READY;
                this.registradores = new int[10];
            }
            public int getId() { return id; }
            public void setContext(int pc, int[] regs) { this.pc = pc; this.registradores = Arrays.copyOf(regs, regs.length); }
            public int getPc() { return pc; }
            public int[] getPageTable() { return pageTable; }
            public int[] getRegistradores() { return registradores; }
            public ProcessState getState() { return state; }
            public void setState(ProcessState state) { this.state = state; }
        }

        public class ProcessManager {
            private List<PCB> pcbList; private Queue<PCB> readyQueue; private PCB runningProcess; private int nextProcessId;
            private final Object schedulerLock = new Object();

            public ProcessManager() { pcbList = new ArrayList<>(); readyQueue = new LinkedList<>(); runningProcess = null; nextProcessId = 1; }
            public PCB getRunningProcess() { return runningProcess; }
            public Queue<PCB> getReadyQueue() { return readyQueue; }
            public Object getSchedulerLock() { return schedulerLock; }

            public void execAllBlocking() {
                if (mode == ExecutionMode.THREADED) {
                    System.out.println("Comando 'execAll' não está disponível no modo de execução contínuo (threaded).");
                    return;
                }
                if (readyQueue.isEmpty()){
                    System.out.println("Nenhum processo na fila de prontos para executar.");
                    return;
                }
                System.out.println("---------------------------------- Iniciando execução BLOQUEANTE de processos");
                escalonar(false);
                while(runningProcess != null){
                    hw.cpu.step();
                }
                System.out.println("---------------------------------- Todos os processos terminaram (modo bloqueante).");
            }

            public int criaProcesso(Word[] programa) {
                synchronized (schedulerLock) {
                    if (programa == null) { System.out.println("Erro: Programa não encontrado."); return -1; }
                    int[] tabelaPaginas = gm.aloca(programa.length);
                    if (tabelaPaginas == null) { System.out.println("Erro: Falha de alocação de memória."); return -1; }
                    PCB pcb = new PCB(nextProcessId++, tabelaPaginas);
                    utils.loadProgramPaged(programa, pcb.getPageTable());
                    pcbList.add(pcb);
                    readyQueue.add(pcb);
                    System.out.println("Processo " + pcb.getId() + " criado e pronto.");
                    if (mode == ExecutionMode.THREADED) {
                        schedulerLock.notifyAll();
                    }
                    return pcb.getId();
                }
            }

            public void desalocaProcesso(int id) {
                synchronized (schedulerLock) {
                    PCB pcb = findPcbById(id);
                    if (pcb == null) { System.out.println("Erro: Processo com ID " + id + " não encontrado."); return; }
                    gm.desaloca(pcb.getPageTable()); pcbList.remove(pcb); readyQueue.remove(pcb);
                    if (runningProcess != null && runningProcess.getId() == id) {
                        // Se o processo a ser removido estiver em execução, pare a CPU e escalone o próximo
                        runningProcess = null;
                        terminaProcessoAtual();
                    }
                    System.out.println("Processo " + id + " desalocado.");
                }
            }
            
            private PCB findPcbById(int id) {
                for (PCB pcb : pcbList) if (pcb.getId() == id) return pcb;
                return null;
            }

            public void escalonar(boolean processoTerminou) {
                synchronized (schedulerLock) {
                    if (runningProcess != null && !processoTerminou) {
                        runningProcess.setContext(hw.cpu.getContextPC(), hw.cpu.getContextRegs());
                        runningProcess.setState(ProcessState.READY);
                        readyQueue.add(runningProcess);
                        System.out.println("Processo " + runningProcess.getId() + " salvo e movido para a fila de prontos.");
                    }
                    if (readyQueue.isEmpty()) {
                        runningProcess = null;
                        hw.cpu.stop();
                        if (mode == ExecutionMode.BLOCKING) {
                           System.out.println("---------------------------------- Fila de prontos vazia. Fim do 'execAll'.");
                        } else {
                           System.out.println("---------------------------------- Fila de prontos vazia. CPU em espera.");
                        }
                        return;
                    }
                    runningProcess = readyQueue.poll();
                    runningProcess.setState(ProcessState.RUNNING);
                    hw.cpu.setContext(runningProcess.getPc(), runningProcess.getRegistradores());
                    hw.cpu.setMMU(runningProcess.getPageTable(), TAM_PG);
                    hw.cpu.resetInstructionCounter();
                    hw.cpu.start();
                    System.out.println(">>> Assumindo CPU: Processo " + runningProcess.getId());
                }
            }

            public void terminaProcessoAtual() {
                synchronized (schedulerLock) {
                    if (runningProcess == null) return;
                    System.out.println("Processo " + runningProcess.getId() + " terminou.");
                    runningProcess.setState(ProcessState.TERMINATED);
                    gm.desaloca(runningProcess.getPageTable());
                    pcbList.remove(runningProcess);
                    runningProcess = null;
                    escalonar(true);
                }
            }

            public void listAllProcesses() { 
                synchronized (schedulerLock) {
                    System.out.println("Lista de todos os processos:");
                    if (pcbList.isEmpty()) { System.out.println("Nenhum processo no sistema."); return; }
                    for (PCB pcb : pcbList) {
                        System.out.println("  ID: " + pcb.getId() + ", Estado: " + pcb.getState() + ", PC: " + pcb.getPc() + ", Tabela: " + Arrays.toString(pcb.getPageTable()));
                    }
                }
            }

            public void dumpProcess(int id) { 
                synchronized (schedulerLock) {
                    PCB pcb = findPcbById(id);
                    if (pcb == null) { System.out.println("Erro: Processo com ID " + id + " não encontrado."); return; }
                    System.out.println("--- Dump do Processo ID: " + pcb.getId() + " ---");
                    System.out.println("  Estado: " + pcb.getState() + ", PC Lógico: " + pcb.getPc());
                    System.out.println("  Tabela de Páginas: " + Arrays.toString(pcb.getPageTable()));
                    System.out.println("  Conteúdo da Memória (visão física):");
                    for (int frame : pcb.getPageTable()) {
                        int start = frame * TAM_PG; int end = start + TAM_PG;
                        System.out.println("    Frame " + frame + " (Endereços Físicos " + start + "-" + (end - 1) + "):");
                        utils.dump(start, end);
                    }
                    System.out.println("--- Fim do Dump ---");
                }
            }
        }
    }

    public class SchedulerExecutor implements Runnable {
        private SO so;
        public SchedulerExecutor(SO so) { this.so = so; }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    synchronized (so.gp.getSchedulerLock()) {
                        while (so.gp.getReadyQueue().isEmpty() && so.gp.getRunningProcess() == null) {
                            so.gp.getSchedulerLock().wait();
                        }
                        if (so.gp.getRunningProcess() == null && !so.gp.getReadyQueue().isEmpty()) {
                            so.gp.escalonar(false);
                        }
                    }
                    if (so.gp.getRunningProcess() != null) {
                        so.hw.cpu.step();
                    }
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    System.out.println("Thread do escalonador interrompida. Encerrando.");
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public HW hw; public SO so; public Programs progs;
    public Sistema(int tamMem) { hw = new HW(tamMem); so = new SO(hw); progs = new Programs(); }

    public void run() {
        System.out.println("Sistema Operacional iniciado em modo BLOQUEANTE.");
        System.out.println("Use 'execAll' para rodar processos ou 'thread2' para ativar o modo contínuo.");
        
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            String[] command = scanner.nextLine().trim().split("\\s+");
            switch (command[0].toLowerCase()) {
                case "new":
                    if (command.length > 1) so.gp.criaProcesso(progs.retrieveProgram(command[1]));
                    else System.out.println("Uso: new <nomeDoPrograma>");
                    break;
                case "rm":
                    if (command.length > 1) try { so.gp.desalocaProcesso(Integer.parseInt(command[1])); } catch (NumberFormatException e) { System.out.println("ID inválido."); }
                    else System.out.println("Uso: rm <id>");
                    break;
                case "ps":
                    so.gp.listAllProcesses();
                    break;
                case "dump":
                     if (command.length > 1) try { so.gp.dumpProcess(Integer.parseInt(command[1])); } catch (NumberFormatException e) { System.out.println("ID inválido."); }
                    else System.out.println("Uso: dump <id>");
                    break;
                case "dumpm":
                    if (command.length > 2) try { so.utils.dump(Integer.parseInt(command[1]), Integer.parseInt(command[2])); } catch (NumberFormatException e) { System.out.println("Endereços inválidos."); }
                    else System.out.println("Uso: dumpM <inicio> <fim>");
                    break;
                case "traceon":
                    hw.cpu.setDebug(true); System.out.println("Modo trace ativado.");
                    break;
                case "traceoff":
                    hw.cpu.setDebug(false); System.out.println("Modo trace desativado.");
                    break;
                case "help":
                     System.out.println("Comandos: new <prog>, rm <id>, ps, dump <id>, dumpm <ini> <fim>, execall, thread2, traceon, traceoff, exit");
                    break;
                case "exit":
                    System.exit(0);
                    break;
                case "execall":
                    so.gp.execAllBlocking();
                    break;
                case "thread2":
                    so.activateThreadedMode();
                    break;
                default:
                    if (!command[0].isEmpty()) System.out.println("Comando desconhecido: " + command[0]);
            }
        }
    }

    public static void main(String args[]) { new Sistema(1024).run(); }
    public class Program {
        public String name; public Word[] image;
        public Program(String n, Word[] i) { name = n; image = i; }
    }
    public class Programs {
        public Word[] retrieveProgram(String pname) {
            for (Program p : progs) if (p != null && p.name.equals(pname)) return p.image;
            return null;
        }
        public Program[] progs = {
            new Program("fatorial", new Word[]{
                new Word(Opcode.LDI, 0, -1, 5),      // 0  r0 = 5 (fatorial de 5)
                new Word(Opcode.LDI, 1, -1, 1),      // 1  r1 = 1 (resultado)
                new Word(Opcode.LDI, 6, -1, 1),      // 2  r6 = 1 (para subtrair)
                new Word(Opcode.LDI, 7, -1, 13),     // 3  r7 = 13 (endereço para salvar resultado)
                new Word(Opcode.JMPIE, 7, 0, 0),     // 4  se r0==0, pula para o fim
                new Word(Opcode.MULT, 1, 0, -1),     // 5  r1 = r1 * r0
                new Word(Opcode.SUB, 0, 6, -1),      // 6  r0 = r0 - 1
                new Word(Opcode.JMP, -1, -1, 4),     // 7  volta para o teste
                new Word(Opcode.STD, 1, -1, 13),     // 8  mem[13] = r1 (resultado)
                new Word(Opcode.LDI, 8, -1, 2),      // 9  SYSCALL para printar
                new Word(Opcode.LDI, 9, -1, 13),     // 10 endereço do que printar
                new Word(Opcode.SYSCALL,-1,-1,-1),   // 11
                new Word(Opcode.STOP, -1, -1, -1),   // 12
                new Word(Opcode.DATA, -1, -1, -1)}),  // 13 (espaço para resultado)
                
            new Program("fatorialV2",
						new Word[] {
								new Word(Opcode.LDI, 0, -1, 5), // numero para colocar na memoria, ou pode ser lido
								new Word(Opcode.STD, 0, -1, 19),
								new Word(Opcode.LDD, 0, -1, 19),
								new Word(Opcode.LDI, 1, -1, -1),
								new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
								new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
								new Word(Opcode.LDI, 1, -1, 1),
								new Word(Opcode.LDI, 6, -1, 1),
								new Word(Opcode.LDI, 7, -1, 13),
								new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula para STD (Stop-1)
								new Word(Opcode.MULT, 1, 0, -1),
								new Word(Opcode.SUB, 0, 6, -1),
								new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
								new Word(Opcode.STD, 1, -1, 18),
								new Word(Opcode.LDI, 8, -1, 2), // escrita
								new Word(Opcode.LDI, 9, -1, 18), // endereco com valor a escrever
								new Word(Opcode.SYSCALL, -1, -1, -1),
								new Word(Opcode.STOP, -1, -1, -1), // POS 17
								new Word(Opcode.DATA, -1, -1, -1), // POS 18
								new Word(Opcode.DATA, -1, -1, -1) } // POS 19
				),

				new Program("progMinimo",
						new Word[] {
								new Word(Opcode.LDI, 0, -1, 999),
								new Word(Opcode.STD, 0, -1, 8),
								new Word(Opcode.STD, 0, -1, 9),
								new Word(Opcode.STD, 0, -1, 10),
								new Word(Opcode.STD, 0, -1, 11),
								new Word(Opcode.STD, 0, -1, 12),
								new Word(Opcode.STOP, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // 7
								new Word(Opcode.DATA, -1, -1, -1), // 8
								new Word(Opcode.DATA, -1, -1, -1), // 9
								new Word(Opcode.DATA, -1, -1, -1), // 10
								new Word(Opcode.DATA, -1, -1, -1), // 11
								new Word(Opcode.DATA, -1, -1, -1), // 12
								new Word(Opcode.DATA, -1, -1, -1) // 13
						}),

				new Program("fibonacci10",
						new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 20),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 21),
								new Word(Opcode.LDI, 0, -1, 22),
								new Word(Opcode.LDI, 6, -1, 6),
								new Word(Opcode.LDI, 7, -1, 31),
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 1, -1),
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.ADD, 1, 2, -1),
								new Word(Opcode.ADD, 2, 3, -1),
								new Word(Opcode.STX, 0, 2, -1),
								new Word(Opcode.ADDI, 0, -1, 1),
								new Word(Opcode.SUB, 7, 0, -1),
								new Word(Opcode.JMPIG, 6, 7, -1),
								new Word(Opcode.STOP, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // POS 20
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1) // ate aqui - serie de fibonacci ficara armazenada
						}),

				new Program("fibonacci10v2",
						new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 20),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 21),
								new Word(Opcode.LDI, 0, -1, 22),
								new Word(Opcode.LDI, 6, -1, 6),
								new Word(Opcode.LDI, 7, -1, 31),
								new Word(Opcode.MOVE, 3, 1, -1),
								new Word(Opcode.MOVE, 1, 2, -1),
								new Word(Opcode.ADD, 2, 3, -1),
								new Word(Opcode.STX, 0, 2, -1),
								new Word(Opcode.ADDI, 0, -1, 1),
								new Word(Opcode.SUB, 7, 0, -1),
								new Word(Opcode.JMPIG, 6, 7, -1),
								new Word(Opcode.STOP, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // POS 20
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1) // ate aqui - serie de fibonacci ficara armazenada
						}),
				new Program("fibonacciREAD",
						new Word[] {
								// mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 8, -1, 1), // leitura
								new Word(Opcode.LDI, 9, -1, 55), // endereco a guardar o tamanho da serie de fib a gerar
																	// - pode ser de 1 a 20
								new Word(Opcode.SYSCALL, -1, -1, -1),
								new Word(Opcode.LDD, 7, -1, 55),
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 7, -1),
								new Word(Opcode.LDI, 4, -1, 36), // posicao para qual ira pular (stop) *
								new Word(Opcode.LDI, 1, -1, -1), // caso negativo
								new Word(Opcode.STD, 1, -1, 41),
								new Word(Opcode.JMPIL, 4, 7, -1), // pula pra stop caso negativo *
								new Word(Opcode.JMPIE, 4, 7, -1), // pula pra stop caso 0
								new Word(Opcode.ADDI, 7, -1, 41), // fibonacci + posição do stop
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 41), // 25 posicao de memoria onde inicia a serie de
																	// fibonacci gerada
								new Word(Opcode.SUBI, 3, -1, 1), // se 1 pula pro stop
								new Word(Opcode.JMPIE, 4, 3, -1),
								new Word(Opcode.ADDI, 3, -1, 1),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 42),
								new Word(Opcode.SUBI, 3, -1, 2), // se 2 pula pro stop
								new Word(Opcode.JMPIE, 4, 3, -1),
								new Word(Opcode.LDI, 0, -1, 43),
								new Word(Opcode.LDI, 6, -1, 25), // salva posição de retorno do loop
								new Word(Opcode.LDI, 5, -1, 0), // salva tamanho
								new Word(Opcode.ADD, 5, 7, -1),
								new Word(Opcode.LDI, 7, -1, 0), // zera (inicio do loop)
								new Word(Opcode.ADD, 7, 5, -1), // recarrega tamanho
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 1, -1),
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.ADD, 1, 2, -1),
								new Word(Opcode.ADD, 2, 3, -1),
								new Word(Opcode.STX, 0, 2, -1),
								new Word(Opcode.ADDI, 0, -1, 1),
								new Word(Opcode.SUB, 7, 0, -1),
								new Word(Opcode.JMPIG, 6, 7, -1), // volta para o inicio do loop
								new Word(Opcode.STOP, -1, -1, -1), // POS 36
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // POS 41
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1)
						}),
				new Program("PB",
						new Word[] {
								// dado um inteiro em alguma posição de memória,
								// se for negativo armazena -1 na saída; se for positivo responde o fatorial do
								// número na saída
								new Word(Opcode.LDI, 0, -1, 7), // numero para colocar na memoria
								new Word(Opcode.STD, 0, -1, 50),
								new Word(Opcode.LDD, 0, -1, 50),
								new Word(Opcode.LDI, 1, -1, -1),
								new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
								new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
								new Word(Opcode.LDI, 1, -1, 1),
								new Word(Opcode.LDI, 6, -1, 1),
								new Word(Opcode.LDI, 7, -1, 13),
								new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula pra STD (Stop-1)
								new Word(Opcode.MULT, 1, 0, -1),
								new Word(Opcode.SUB, 0, 6, -1),
								new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
								new Word(Opcode.STD, 1, -1, 15),
								new Word(Opcode.STOP, -1, -1, -1), // POS 14
								new Word(Opcode.DATA, -1, -1, -1) // POS 15
						}),
				new Program("PC",
						new Word[] {
								// Para um N definido (10 por exemplo)
								// o programa ordena um vetor de N números em alguma posição de memória;
								// ordena usando bubble sort
								// loop ate que não swap nada
								// passando pelos N valores
								// faz swap de vizinhos se da esquerda maior que da direita
								new Word(Opcode.LDI, 7, -1, 5), // TAMANHO DO BUBBLE SORT (N)
								new Word(Opcode.LDI, 6, -1, 5), // aux N
								new Word(Opcode.LDI, 5, -1, 46), // LOCAL DA MEMORIA
								new Word(Opcode.LDI, 4, -1, 47), // aux local memoria
								new Word(Opcode.LDI, 0, -1, 4), // colocando valores na memoria
								new Word(Opcode.STD, 0, -1, 46),
								new Word(Opcode.LDI, 0, -1, 3),
								new Word(Opcode.STD, 0, -1, 47),
								new Word(Opcode.LDI, 0, -1, 5),
								new Word(Opcode.STD, 0, -1, 48),
								new Word(Opcode.LDI, 0, -1, 1),
								new Word(Opcode.STD, 0, -1, 49),
								new Word(Opcode.LDI, 0, -1, 2),
								new Word(Opcode.STD, 0, -1, 50), // colocando valores na memoria até aqui - POS 13
								new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 1
								new Word(Opcode.STD, 3, -1, 99),
								new Word(Opcode.LDI, 3, -1, 22), // Posicao para pulo CHAVE 2
								new Word(Opcode.STD, 3, -1, 98),
								new Word(Opcode.LDI, 3, -1, 38), // Posicao para pulo CHAVE 3
								new Word(Opcode.STD, 3, -1, 97),
								new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 4 (não usada)
								new Word(Opcode.STD, 3, -1, 96),
								new Word(Opcode.LDI, 6, -1, 0), // r6 = r7 - 1 POS 22
								new Word(Opcode.ADD, 6, 7, -1),
								new Word(Opcode.SUBI, 6, -1, 1), // ate aqui
								new Word(Opcode.JMPIEM, -1, 6, 97), // CHAVE 3 para pular quando r7 for 1 e r6 0 para
																	// interomper o loop de vez do programa
								new Word(Opcode.LDX, 0, 5, -1), // r0 e ra pegando valores das posições da memoria POS
																// 26
								new Word(Opcode.LDX, 1, 4, -1),
								new Word(Opcode.LDI, 2, -1, 0),
								new Word(Opcode.ADD, 2, 0, -1),
								new Word(Opcode.SUB, 2, 1, -1),
								new Word(Opcode.ADDI, 4, -1, 1),
								new Word(Opcode.SUBI, 6, -1, 1),
								new Word(Opcode.JMPILM, -1, 2, 99), // LOOP chave 1 caso neg procura prox
								new Word(Opcode.STX, 5, 1, -1),
								new Word(Opcode.SUBI, 4, -1, 1),
								new Word(Opcode.STX, 4, 0, -1),
								new Word(Opcode.ADDI, 4, -1, 1),
								new Word(Opcode.JMPIGM, -1, 6, 99), // LOOP chave 1 POS 38
								new Word(Opcode.ADDI, 5, -1, 1),
								new Word(Opcode.SUBI, 7, -1, 1),
								new Word(Opcode.LDI, 4, -1, 0), // r4 = r5 + 1 POS 41
								new Word(Opcode.ADD, 4, 5, -1),
								new Word(Opcode.ADDI, 4, -1, 1), // ate aqui
								new Word(Opcode.JMPIGM, -1, 7, 98), // LOOP chave 2
								new Word(Opcode.STOP, -1, -1, -1), // POS 45
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1)
						})
        };
    }
}