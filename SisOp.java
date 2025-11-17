import java.util.LinkedList;
import java.util.Queue;

// Classe principal do Sistema Operacional. Agrega e inicializa todos os módulos.
public class SisOp {
    
    public Hardware.HW hw;
    public InterruptHandling interruptHandling;
    public SysCallHandling sysCallHandling;
    public Utilities utils;
    public SisOp_GM gm;
    public SisOp_ProcessManager processManager;
    public Sistema.Programs progs;
    
    public final int TAM_PAG = 16;

    public enum ExecutionMode { BLOCKING, THREADED }
    private ExecutionMode mode = ExecutionMode.BLOCKING;
    private boolean schedulerThreadStarted = false;

    // Infraestrutura de I/O (Console)
    public Queue<IORequest> ioQueue;
    public final Object ioLock = new Object();

    // Infraestrutura de Disco (VM)
    public Queue<VM_IORequest> diskQueue;
    public final Object diskLock = new Object();

    public SisOp(Hardware.HW hw, Sistema.Programs progs) {
        this.hw = hw;
        this.progs = progs;
        
        this.interruptHandling = new InterruptHandling(this);
        this.sysCallHandling = new SysCallHandling(this);
        this.hw.cpu.setAddressOfHandlers(this.interruptHandling, this.sysCallHandling);
        this.gm = new SisOp_GM(hw.mem.pos.length, TAM_PAG);
        this.utils = new Utilities(this);
        this.hw.cpu.setUtilities(this.utils);
        this.processManager = new SisOp_ProcessManager(this);

        this.ioQueue = new LinkedList<>();
        this.diskQueue = new LinkedList<>();
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
        schedulerThread.setDaemon(true);
        schedulerThread.start();
        
        Thread devThread = new Thread(this.new DeviceRunnable());
        devThread.setDaemon(true);
        devThread.start();

        Thread diskThread = new Thread(this.new DiskRunnable());
        diskThread.setDaemon(true);
        diskThread.start();
        
        System.out.println("Modo de execução contínuo (threaded) ativado. CPU e Dispositivos estão concorrentes.");
    }

    // Representa um pedido para o CONSOLE
    public static class IORequest {
        public int processId;
        public int opCode; // 1=IN, 2=OUT
        public int memoryAddress;
    }

    // Representa um pedido para o DISCO (VM)
    public static class VM_IORequest {
        public enum OpType { PAGE_IN, PAGE_OUT }
        
        public OpType type;
        public int processId;
        public int pageNumber;
        public int frameNumber;
        public Hardware.Word[] dataToSave; // Usado apenas para PAGE_OUT
    }

    // Thread do Dispositivo (Console)
    public class DeviceRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                IORequest req;
                synchronized (ioLock) {
                    while (ioQueue.isEmpty()) {
                        try { ioLock.wait(); } catch (InterruptedException e) {}
                    }
                    req = ioQueue.poll();
                }

                System.out.println("CONSOLE-IO: Iniciando I/O (op=" + req.opCode + ") para processo " + req.processId);
                try { 
                    Thread.sleep(1500 + (long)(Math.random() * 1500)); 
                } catch (InterruptedException e) {} 

                if (req.opCode == 2) { // OUT
                     int valor = hw.mem.pos[req.memoryAddress].p;
                     System.out.println("====================================================");
                     System.out.println("CONSOLE (OUT): Processo " + req.processId + " escreveu: " + valor);
                     System.out.println("====================================================");
                
                } else if (req.opCode == 1) { // IN
                     int valorLido = (int)(Math.random() * 100); 
                     hw.mem.pos[req.memoryAddress].p = valorLido;
                     System.out.println("CONSOLE (IN): LIDO " + valorLido + " para mem[" + req.memoryAddress + "] (Processo " + req.processId + ")");
                }

                hw.cpu.raiseInterrupt(Hardware.CPU.Interrupts.intIOEnd, req.processId);
            }
        }
    }
    
    // Thread do DISCO (VM)
    public class DiskRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                VM_IORequest req;
                synchronized (diskLock) {
                    while (diskQueue.isEmpty()) {
                        try { 
                            diskLock.wait(); 
                        } catch (InterruptedException e) {}
                    }
                    req = diskQueue.poll();
                }

                System.out.println("DISCO-VM: Iniciando " + req.type + " para P-" + req.processId + ", Pág: " + req.pageNumber + ", Frame: " + req.frameNumber);
                
                try { 
                    Thread.sleep(800 + (long)(Math.random() * 800)); 
                } catch (InterruptedException e) {} 
                
                if (req.type == VM_IORequest.OpType.PAGE_IN) {
                
                    SisOp_ProcessManager.PCB pcb = processManager.findPcbById(req.processId);
                    if (pcb != null) {
                        
                        String progName = pcb.getProgramName();
                        Hardware.Word[] programa = progs.retrieveProgram(progName);

                        if (programa != null) {
                            utils.loadPage(programa, req.pageNumber, req.frameNumber, TAM_PAG);
                        } else {
                            System.out.println("DISCO-VM: ERRO! Programa '" + progName + "' não encontrado.");
                        }
                        
                        System.out.println("DISCO-VM: ...Página " + req.pageNumber + " carregada no Frame " + req.frameNumber);
                    
                        synchronized (processManager.getSchedulerLock()) {
                            hw.cpu.raiseInterruptVM(Hardware.CPU.Interrupts.intPageInComplete, 
                                                    req.processId, req.pageNumber, req.frameNumber);
                            processManager.getSchedulerLock().notifyAll();
                        }
                    } else {
                        System.out.println("DISCO-VM: ERRO! Processo " + req.processId + " não encontrado para Page-In.");
                    }

                } else { // PAGE_OUT
                    System.out.println("DISCO-VM: ...Frame " + req.frameNumber + " (Pág " + req.pageNumber + ") salvo no disco.");
                    
                    synchronized (processManager.getSchedulerLock()) {
                        hw.cpu.raiseInterruptVM(Hardware.CPU.Interrupts.intPageOutComplete, 
                                                req.processId, req.pageNumber, req.frameNumber);
                        processManager.getSchedulerLock().notifyAll();
                    } 
                }
            }
        }
    }

    public class Utilities {
       private SisOp so; 
        public Utilities(SisOp so) { this.so = so; } 

        public void loadPage(Hardware.Word[] programa, int numPagina, int frameDestino, int tamPag) {
            Hardware.Word[] m = so.hw.mem.pos;
            
            int endFisicoBase = frameDestino * tamPag;
            int endLogicoBase = numPagina * tamPag;

            for (int i = 0; i < tamPag; i++) {
                int posLogica = endLogicoBase + i;
                int posFisica = endFisicoBase + i;

                if (posLogica < programa.length) {
                    m[posFisica] = new Hardware.Word(programa[posLogica].opc, 
                                                    programa[posLogica].r1, 
                                                    programa[posLogica].r2, 
                                                    programa[posLogica].p);
                } else {
                    m[posFisica] = new Hardware.Word(Hardware.CPU.Opcode.___, -1, -1, -1);
                }
            }
        }

        public void dump(Hardware.Word w) { 
            System.out.print("[ " + w.opc + ", " + w.r1 + ", " + w.r2 + ", " + w.p + " ]"); 
        }
        
        public void dump(int ini, int fim) {
            for (int i = ini; i < fim && i < so.hw.mem.pos.length; i++) {
                System.out.print(i + ":  ");
                dump(so.hw.mem.pos[i]);
                System.out.println();
            }
        }
    }
    
    public class InterruptHandling {
        private SisOp so;
        private VM_IORequest waitingPageIn = null;
        public InterruptHandling(SisOp so) { this.so = so; }
        
        public void handle(Hardware.CPU.Interrupts irpt) {
            switch (irpt) {
                case intQuantumEnd:
                    System.out.println("                                               Interrupcao de TEMPO");
                    so.processManager.escalonar(false);
                    break;

                case intIOEnd: // Interrupção do CONSOLE
                    System.out.println("                                               Interrupcao de CONSOLE-I/O");
                    int processId = so.hw.cpu.getIOProcessId();
                    so.processManager.desbloqueiaProcesso(processId);
                    break;

                case intPageFault:
                    System.out.println("                                               Interrupcao: PAGE FAULT");
                
                    SisOp_ProcessManager.PCB processo = so.processManager.getRunningProcess();
                    int pc = so.hw.cpu.getContextPC(); 
                    
                    if (processo == null) {
                        System.out.println("                                               ERRO: Page Fault sem processo rodando!");
                        return;
                    }

                    int enderecoFaltante = so.hw.cpu.getFaultingAddress();
                    int paginaFaltante = enderecoFaltante / so.TAM_PAG;
                    
                    System.out.println("                                               P-" + processo.getId() + " falhou ao acessar End: " + enderecoFaltante + " (Pág: " + paginaFaltante + ") no PC=" + pc);

                    int frameLivre = so.gm.alocaFrameLivre();

                    if (frameLivre >= 0) {
                        // --- CENÁRIO 1: Frame livre encontrado ---
                        System.out.println("                                               GM alocou Frame " + frameLivre + " para Page-In.");
                        
                        SisOp_ProcessManager.PageTableEntry pte = processo.getPageTable()[paginaFaltante]; 
                        pte.frameNumber = frameLivre;
                        
                        VM_IORequest req = new VM_IORequest();
                        req.type = VM_IORequest.OpType.PAGE_IN;
                        req.processId = processo.getId();
                        req.pageNumber = paginaFaltante; 
                        req.frameNumber = frameLivre;

                        synchronized (so.diskLock) {
                            so.diskQueue.add(req);
                            so.diskLock.notify();
                        }
                        
                        so.processManager.bloqueiaProcessoVM();
                        
                    } else {
                        // --- CENÁRIO 2: Memória cheia (Vitimização) ---
                        System.out.println("                                               MEMÓRIA CHEIA. Iniciar Vitimização.");

                        int frameVitima = so.gm.findVictimFrame();
                        SisOp_ProcessManager.VictimInfo vitima = so.processManager.findAndInvalidateVictim(frameVitima);

                        if (vitima == null) {
                            System.out.println("                                               ERRO FATAL: GM vitimou frame " + frameVitima + " mas PM não achou dono.");
                            so.processManager.terminaProcessoAtual();
                            break;
                        }

                        VM_IORequest pageInRequest = new VM_IORequest();
                        pageInRequest.type = VM_IORequest.OpType.PAGE_IN;
                        pageInRequest.processId = processo.getId();
                        pageInRequest.pageNumber = paginaFaltante;
                        pageInRequest.frameNumber = frameVitima; 

                        this.waitingPageIn = pageInRequest; 
                        System.out.println("                                               PAGE-IN de P-" + processo.getId() + " (Pág " + paginaFaltante + ") estacionado, esperando Frame " + frameVitima + ".");

                        if (vitima.pte.dirty) {
                            System.out.println("                                               Vítima (P-" + vitima.pcbOwner.getId() + ", Pág " + vitima.pageNumber + ") está 'dirty'. Enfileirando PAGE_OUT.");

                            VM_IORequest pageOutRequest = new VM_IORequest();
                            pageOutRequest.type = VM_IORequest.OpType.PAGE_OUT;
                            pageOutRequest.processId = vitima.pcbOwner.getId();
                            pageOutRequest.pageNumber = vitima.pageNumber;
                            pageOutRequest.frameNumber = frameVitima;
                            
                            // (Aqui precisaríamos copiar os dados do frame para 'dataToSave')

                            synchronized (so.diskLock) {
                                so.diskQueue.add(pageOutRequest);
                                so.diskLock.notify();
                            }
                        } else {
                            System.out.println("                                               Vítima está 'limpa'. Enfileirando PAGE_IN (estacionado) imediatamente.");
                            synchronized (so.diskLock) {
                                so.diskQueue.add(this.waitingPageIn);
                                so.diskLock.notify();
                                this.waitingPageIn = null; 
                            }
                        }

                        so.processManager.bloqueiaProcessoVM();
                    }
                    break;

                case intPageInComplete:
                    System.out.println("                                               Interrupcao: PAGE-IN Completo");
                    
                    int procId = so.hw.cpu.getVMProcessId();
                    int pageNum = so.hw.cpu.getVMPageNumber();
                    int frameNum = so.hw.cpu.getVMFrameNumber();
                    
                    SisOp_ProcessManager.PCB pcb = so.processManager.findPcbById(procId);
                    if (pcb != null) {
                        SisOp_ProcessManager.PageTableEntry pte = pcb.getPageTable()[pageNum];
                        pte.valid = true;
                        pte.dirty = false; 
                        System.out.println("                                               PTE de P-" + procId + " (Pág " + pageNum + ") agora é VÁLIDA no Frame " + frameNum);
                    } else {
                        System.out.println("                                               AVISO: Processo P-" + procId + " não encontrado para validar PTE.");
                    }
                    
                    so.processManager.desbloqueiaProcessoVM(procId);
                    break;

                case intPageOutComplete:
                     System.out.println("                                               Interrupcao: PAGE-OUT Completo");
                     
                     if (this.waitingPageIn != null) {
                        System.out.println("                                               Frame livre. Desestacionando e enfileirando o PAGE_IN.");
                        
                        synchronized (so.diskLock) {
                            so.diskQueue.add(this.waitingPageIn);
                            so.diskLock.notify();
                            this.waitingPageIn = null; 
                        }
                     } else {
                        System.out.println("                                               AVISO: Page-Out concluído, mas não havia Page-In esperando.");
                        int frameLiberado = so.hw.cpu.getVMFrameNumber();
                        so.gm.liberaFrame(frameLiberado);
                     }
                    break;

                case intEnderecoInvalido:
                case intInstrucaoInvalida:
                case intOverflow:
                    System.out.println("                                               ERRO: Interrupcao " + irpt);
                    so.processManager.terminaProcessoAtual();
                    break;
                default:
                    System.out.println("                                               Interrupcao " + irpt);
            }
        }
    }

    public class SysCallHandling {
        private SisOp so;
        public SysCallHandling(SisOp so) { this.so = so; }
        
        public void stop() { 
            so.processManager.terminaProcessoAtual(); 
        }

        public void handle() {
            int regOp = so.hw.cpu.getContextRegs()[8];
            int regMem = so.hw.cpu.getContextRegs()[9];

            if (regOp == 1 || regOp == 2) { 
                System.out.print("SYSCALL - I/O (Console) solicitado pelo Processo " + so.processManager.getRunningProcess().getId());
                
                int fis = so.hw.cpu.toPhysical(regMem); 
                
                if (fis < 0) {
                    System.out.println("... ERRO: Endereço de I/O inválido ou Page Fault pendente.");
                    return; 
                }

                IORequest req = new IORequest();
                req.processId = so.processManager.getRunningProcess().getId();
                req.opCode = regOp;
                req.memoryAddress = fis;

                synchronized (so.ioLock) {
                    so.ioQueue.add(req);
                    so.ioLock.notify();
                }

                so.processManager.bloqueiaProcessoAtual(); 

            } else {
                System.out.println("SYSCALL - Operação desconhecida: " + regOp);
            }
        }
    }
}