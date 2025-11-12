// Classe principal do Sistema Operacional. Agrega e inicializa todos os módulos.
public class SisOp {
    
    public Hardware.HW hw;
    public InterruptHandling interruptHandling;
    public SysCallHandling sysCallHandling;
    public Utilities utils;
    public SisOp_GM gm;
    public SisOp_ProcessManager processManager;
    
    public final int TAM_PAG = 16;

    public enum ExecutionMode { BLOCKING, THREADED }
    private ExecutionMode mode = ExecutionMode.BLOCKING;
    private boolean schedulerThreadStarted = false;

    public SisOp(Hardware.HW hw) {
        this.hw = hw;
        // Inicializa todos os módulos do SO
        this.interruptHandling = new InterruptHandling(this);
        this.sysCallHandling = new SysCallHandling(this);
        this.hw.cpu.setAddressOfHandlers(this.interruptHandling, this.sysCallHandling);
        this.gm = new SisOp_GM(hw.mem.pos.length, TAM_PAG);
        this.utils = new Utilities(hw);
        this.hw.cpu.setUtilities(this.utils);
        this.processManager = new SisOp_ProcessManager(this);
    }
    
    public ExecutionMode getMode() {
        return this.mode;
    }

    public void activateThreadedMode(int quantum) {
        if (schedulerThreadStarted) {
            System.out.println("O modo de execução com threads já está ativo.");
            return;
        }
        if (processManager.getRunningProcess() != null) {
            System.out.println("Não é possível mudar de modo enquanto um processo está em execução. Aguarde o fim do 'execAll'.");
            return;
        }
        this.mode = ExecutionMode.THREADED;
        this.schedulerThreadStarted = true;
        Thread schedulerThread = new Thread(new Sistema.SchedulerExecutor(this, quantum));
        schedulerThread.start();
        System.out.println("Modo de execução contínuo (threaded) ativado.");
    }

    // --- CLASSES INTERNAS PARA MÓDULOS MENORES ---

    public class Utilities {
        private Hardware.HW hw;

        public Utilities(Hardware.HW hw) { this.hw = hw; }

        public void loadProgram(Hardware.Word[] prog, int[] tabelaPaginas, int tamPag) {
            Hardware.Word[] m = hw.mem.pos;
            for (int i = 0; i < prog.length; i++) {
                int pag = i / tamPag; 
                int off = i % tamPag;
                int frame = tabelaPaginas[pag]; 
                int fis = frame * tamPag + off;
                m[fis] = new Hardware.Word(prog[i].opc, prog[i].r1, prog[i].r2, prog[i].p);
            }
        }

        public void dump(Hardware.Word w) { 
            System.out.print("[ " + w.opc + ", " + w.r1 + ", " + w.r2 + ", " + w.p + " ]"); 
        }
        public void dump(int ini, int fim) {
            for (int i = ini; i < fim; i++) {
                System.out.print(i + ":  ");
                dump(hw.mem.pos[i]);
                System.out.println();
            }
        }
    }
    
    public class InterruptHandling {
        private SisOp so;
        public InterruptHandling(SisOp so) { this.so = so; }
        public void handle(Hardware.CPU.Interrupts irpt) {
            switch (irpt) {
                case intQuantumEnd:
                    System.out.println("\n\nInterrupcao de TEMPO");
                    so.processManager.escalonar(false);
                    break;
                case intEnderecoInvalido:
                case intInstrucaoInvalida:
                case intOverflow:
                    System.out.println("\n\nERRO: Interrupcao " + irpt);
                    so.processManager.terminaProcessoAtual();
                    break;
                default:
                    System.out.println("\n\nInterrupcao " + irpt);
            }
        }
    }

    public class SysCallHandling {
        private SisOp so;
        public SysCallHandling(SisOp so) { this.so = so; }
        public void stop() { so.processManager.terminaProcessoAtual(); }
        public void handle() {
            if (so.hw.cpu.getContextRegs()[8] == 2) {
                System.out.print("SYSCALL - Print do Processo " + so.processManager.getRunningProcess().getId() + ": ");
                int fis = so.hw.cpu.toPhysical(so.hw.cpu.getContextRegs()[9]);
                if (fis >= 0) {
                    System.out.println("OUT: " + so.hw.mem.pos[fis].p);
                }
            }
        }
    }
}