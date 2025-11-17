import java.util.LinkedList;
import java.util.Queue;
import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.util.Arrays;

// Classe principal do Sistema Operacional.
public class SisOp {
    
    public Hardware.HW hw;
    public InterruptHandling interruptHandling;
    public SysCallHandling sysCallHandling;
    public Utilities utils;
    public SisOp_GM gm;
    public SisOp_ProcessManager processManager;
    public DeviceManager deviceManager;
    public DiskManager diskManager;
    public VMManager vmManager;
    public Logger logger;
    private Sistema sistemaHost; 
    public final int TAM_PAG = 16;

    public enum ExecutionMode { BLOCKING, THREADED }
    private ExecutionMode mode = ExecutionMode.BLOCKING;
    private boolean schedulerThreadStarted = false;

    public SisOp(Hardware.HW hw, Sistema host) {
        this.hw = hw;
        this.sistemaHost = host; 
        this.logger = new Logger();
        this.interruptHandling = new InterruptHandling(this);
        this.sysCallHandling = new SysCallHandling(this);
        this.hw.cpu.setAddressOfHandlers(this.interruptHandling, this.sysCallHandling);
        this.gm = new SisOp_GM(hw.mem.pos.length, TAM_PAG);
        this.utils = new Utilities(hw);
        this.hw.cpu.setUtilities(this.utils);
        this.processManager = new SisOp_ProcessManager(this);
        this.vmManager = new VMManager(this);
        this.diskManager = new DiskManager(this);
        new Thread(this.diskManager).start(); 
        this.deviceManager = new DeviceManager(this);
        new Thread(this.deviceManager).start(); 
    }
    
    public ExecutionMode getMode() { return this.mode; }
    public Sistema getSistemaHost() { return this.sistemaHost; }

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

    // --- CLASSES INTERNAS ---

    public class Utilities {
        private Hardware.HW hw;
        public Utilities(Hardware.HW hw) { this.hw = hw; }
        public void loadPage(Hardware.Word[] program, int frame, int page) {
            Hardware.Word[] m = hw.mem.pos;
            int startAddr = page * TAM_PAG;
            int endAddr = Math.min(startAddr + TAM_PAG, program.length);
            int frameStart = frame * TAM_PAG;
            for (int i = 0; i < (endAddr - startAddr); i++) {
                if (startAddr + i < program.length) { 
                    m[frameStart + i] = new Hardware.Word(
                        program[startAddr + i].opc, 
                        program[startAddr + i].r1, 
                        program[startAddr + i].r2, 
                        program[startAddr + i].p
                    );
                }
            }
        }
        public Hardware.Word[] savePage(int frame) {
            Hardware.Word[] pageData = new Hardware.Word[TAM_PAG];
            Hardware.Word[] m = hw.mem.pos;
            int frameStart = frame * TAM_PAG;
            for (int i = 0; i < TAM_PAG; i++) {
                Hardware.Word w = m[frameStart + i];
                pageData[i] = new Hardware.Word(w.opc, w.r1, w.r2, w.p);
            }
            return pageData;
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
        private SisOp_ProcessManager.PCB lastIOProcess; 
        public InterruptHandling(SisOp so) { this.so = so; }
        public void setLastIOProcess(SisOp_ProcessManager.PCB pcb) {
            this.lastIOProcess = pcb;
        }
        public void handle(Hardware.CPU.Interrupts irpt) {
            SisOp_ProcessManager.PCB pcb = so.processManager.getRunningProcess();
            switch (irpt) {
                case intPageFault:
                    System.out.println("\n\nInterrupcao: PAGE FAULT");
                    int faultedPage = so.hw.cpu.getFaultedPage();
                    if (pcb != null) {
                        pcb.setContext(so.hw.cpu.getContextPC(), so.hw.cpu.getContextRegs());
                        so.vmManager.handlePageFault(pcb, faultedPage);
                    } else {
                        System.out.println("Erro: Page Fault sem processo rodando.");
                    }
                    break;
                case intQuantumEnd:
                    System.out.println("\n\nInterrupcao de TEMPO");
                    so.processManager.escalonar(false);
                    break;
                case intIO:
                    System.out.println("\n\nInterrupcao de E/S (Console Concluiu)");
                    if (lastIOProcess != null) {
                        so.processManager.unblockProcess(lastIOProcess, "Fim_E/S_Dispositivo_Legado");
                        lastIOProcess = null;
                    }
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
        public void stop() { 
            so.processManager.terminaProcessoAtual(); 
        }
        public void handle() {
            int op = so.hw.cpu.getContextRegs()[8];
            int addr = so.hw.cpu.getContextRegs()[9];
            if (op == 1 || op == 2) {
                SisOp_ProcessManager.PCB pcb = so.processManager.getRunningProcess();
                int pag = addr / so.TAM_PAG;
                if (pag < 0 || pag >= pcb.getPageTable().length) {
                    so.interruptHandling.handle(Hardware.CPU.Interrupts.intEnderecoInvalido);
                    return; 
                }
                if (!pcb.getPageTable()[pag].valid) {
                    System.out.println("--- SysCall: Página " + pag + " (para E/S) não está na memória. Disparando Page Fault. ---");
                    pcb.setContext(so.hw.cpu.getContextPC(), so.hw.cpu.getContextRegs());
                    so.hw.cpu.triggerPageFault(pag);
                    so.interruptHandling.handle(Hardware.CPU.Interrupts.intPageFault);
                    return; 
                }
                so.hw.cpu.setContext(so.hw.cpu.getContextPC() + 1, so.hw.cpu.getContextRegs());
                pcb.setContext(so.hw.cpu.getContextPC(), so.hw.cpu.getContextRegs());
                IORequest req = new IORequest(pcb, op, addr);
                so.deviceManager.addRequest(req);
                so.processManager.blockCurrentProcess("E/S_Console");
            } else {
                System.out.println("SYSCALL: Operação " + op + " desconhecida.");
                so.hw.cpu.setContext(so.hw.cpu.getContextPC() + 1, so.hw.cpu.getContextRegs());
            }
        }
    }

    public class IORequest {
        public SisOp_ProcessManager.PCB pcb;
        public int operation; // 1 = READ, 2 = WRITE
        public int address;
        public IORequest(SisOp_ProcessManager.PCB pcb, int operation, int address) {
            this.pcb = pcb; this.operation = operation; this.address = address;
        }
    }

    public class DeviceManager implements Runnable {
        private SisOp so;
        private Queue<IORequest> requestQueue;
        private final Object ioQueueLock = new Object(); 
        public DeviceManager(SisOp so) {
            this.so = so;
            this.requestQueue = new LinkedList<>();
        }
        public void addRequest(IORequest request) {
            synchronized (ioQueueLock) {
                requestQueue.add(request);
                ioQueueLock.notify(); 
            }
        }
        @Override
        public void run() {
            while (true) {
                IORequest currentRequest;
                synchronized (ioQueueLock) {
                    while (requestQueue.isEmpty()) {
                        try { ioQueueLock.wait(); } catch (InterruptedException e) {}
                    }
                    currentRequest = requestQueue.poll();
                }
                System.out.println("--- Dispositivo de E/S: Iniciando operação " + 
                                   (currentRequest.operation == 1 ? "READ" : "WRITE") + 
                                   " para o Processo " + currentRequest.pcb.getId() + " ---");
                try { Thread.sleep(1000); } catch (InterruptedException e) {} 
                if (currentRequest.operation == 1) { 
                    Sistema host = so.getSistemaHost();
                    Object hostLock = host.getIoConsoleLock();
                    int valor = 0;
                    boolean inputValido = false;
                    while (!inputValido) {
                        try {
                            host.startWaitingForIO(currentRequest.pcb.getId());
                            synchronized (hostLock) { hostLock.wait(); }
                            String input = host.getIoInputBuffer();
                            valor = Integer.parseInt(input.trim());
                            inputValido = true;
                        } catch (NumberFormatException e) {
                            System.out.println("--- Dispositivo de E/S: ERRO! Entrada inválida. Tente novamente. ---");
                        } catch (InterruptedException e) {
                            inputValido = true; valor = 0;
                        } catch (Exception e) {}
                    }
                    Hardware.PageTableEntry[] pcbPageTable = currentRequest.pcb.getPageTable();
                    int logicalAddr = currentRequest.address;
                    int pag = logicalAddr / so.TAM_PAG;
                    int off = logicalAddr % so.TAM_PAG;
                    int endFis = -1; 
                    if (pag >= 0 && pag < pcbPageTable.length && pcbPageTable[pag].valid) {
                        int frame = pcbPageTable[pag].frameNumber;
                        endFis = (frame * so.TAM_PAG) + off;
                    }
                    if (endFis >= 0) {
                        so.hw.mem.pos[endFis].p = valor;
                        System.out.println("--- Dispositivo de E/S: Valor " + valor + " escrito no endereço lógico " + currentRequest.address + " (físico " + endFis + "). ---");
                    } else {
                        System.out.println("--- Dispositivo de E/S: ERRO! Tradução de endereço falhou (página " + pag + " não é válida?). ---");
                    }
                } else if (currentRequest.operation == 2) { 
                    Hardware.PageTableEntry[] pcbPageTable = currentRequest.pcb.getPageTable();
                    int logicalAddr = currentRequest.address;
                    int pag = logicalAddr / so.TAM_PAG;
                    int off = logicalAddr % so.TAM_PAG;
                    int endFis = -1;
                    if (pag >= 0 && pag < pcbPageTable.length && pcbPageTable[pag].valid) {
                        int frame = pcbPageTable[pag].frameNumber;
                        endFis = (frame * so.TAM_PAG) + off;
                    }
                    int valor = -1;
                    if (endFis >= 0) {
                        valor = so.hw.mem.pos[endFis].p;
                    }
                    System.out.println("\n>>> Dispositivo de E/S (OUT do Processo " + currentRequest.pcb.getId() + "): " + valor);
                    System.out.println("--- Dispositivo de E/S: Escrita concluída. ---");
                }
                so.processManager.unblockProcess(currentRequest.pcb, "Fim_E/S_Console");
            }
        }
    }

    public class VMManager {
        private SisOp so;
        public VMManager(SisOp so) { this.so = so; }
        public void handlePageFault(SisOp_ProcessManager.PCB pcb, int page) {
            System.out.println("--- VMManager: Tratando Page Fault para Processo " + pcb.getId() + ", Página " + page + " ---");
            int frame = so.gm.findFreeFrame();
            if (frame != -1) {
                System.out.println("--- VMManager: Frame livre " + frame + " encontrado.");
                so.gm.occupyFrame(frame, pcb, page);
                so.diskManager.requestLoad(pcb, page, frame);
                so.processManager.blockCurrentProcess("Page_Fault");
            } else {
                System.out.println("--- VMManager: Nenhum frame livre. Iniciando vitimização.");
                int victimFrame = so.gm.selectVictimFrame();
                SisOp_GM.FrameInfo victimInfo = so.gm.getFrameInfo(victimFrame);
                if (victimInfo == null) {
                    System.out.println("--- VMManager: ERRO! Vitimização falhou (frame nulo).");
                    return;
                }
                System.out.println("--- VMManager: Frame " + victimFrame + " (Processo " + victimInfo.pcb.getId() + ", Página " + victimInfo.pageNumber + ") foi vitimado.");
                victimInfo.pcb.getPageTable()[victimInfo.pageNumber].valid = false;
                victimInfo.pcb.getPageTable()[victimInfo.pageNumber].onDisk = true;
                so.gm.setWaiter(victimFrame, pcb);
                so.diskManager.requestSave(victimInfo.pcb, victimInfo.pageNumber, victimFrame);
                so.processManager.blockCurrentProcess("Page_Fault_Vitima");
            }
        }
    }
    
    public class DiskRequest {
        public enum OpType { LOAD_FROM_PROG, LOAD_FROM_SWAP, SAVE_TO_SWAP }
        public OpType type;
        public SisOp_ProcessManager.PCB pcb;
        public int page;
        public int frame;
        public DiskRequest(SisOp_ProcessManager.PCB pcb, int page, int frame) {
            this.type = OpType.SAVE_TO_SWAP;
            this.pcb = pcb; this.page = page; this.frame = frame;
        }
        public DiskRequest(SisOp_ProcessManager.PCB pcb, int page, int frame, boolean fromSwap) {
            this.type = fromSwap ? OpType.LOAD_FROM_SWAP : OpType.LOAD_FROM_PROG;
            this.pcb = pcb; this.page = page; this.frame = frame;
        }
    }

    public class DiskManager implements Runnable {
        private SisOp so;
        private Queue<DiskRequest> diskQueue;
        private final Object diskLock = new Object();
        private Map<Integer, Hardware.Word[]> programStore; 
        private Map<String, Hardware.Word[]> swapStore;      
        public DiskManager(SisOp so) {
            this.so = so;
            this.diskQueue = new LinkedList<>();
            this.programStore = new HashMap<>();
            this.swapStore = new HashMap<>();
        }
        public void requestLoad(SisOp_ProcessManager.PCB pcb, int page, int frame) {
            boolean fromSwap = pcb.getPageTable()[page].onDisk;
            System.out.println("--- DiskManager: Pedido de LOAD (P" + pcb.getId() + ", Pag " + page + ") para Frame " + frame + " (do " + (fromSwap ? "Swap" : "Programa") + ")");
            addRequest(new DiskRequest(pcb, page, frame, fromSwap));
        }
        public void requestSave(SisOp_ProcessManager.PCB pcb, int page, int frame) {
            System.out.println("--- DiskManager: Pedido de SAVE (P" + pcb.getId() + ", Pag " + page + ") do Frame " + frame + " para o Swap");
            addRequest(new DiskRequest(pcb, page, frame));
        }
        private void addRequest(DiskRequest req) {
            synchronized(diskLock) {
                diskQueue.add(req);
                diskLock.notify();
            }
        }
        public void saveProgramToStore(int progId, Hardware.Word[] program) {
            programStore.put(progId, program);
        }
        public void clearSwap(int pcbId) { }
        @Override
        public void run() {
            while (true) {
                DiskRequest req;
                synchronized (diskLock) {
                    while (diskQueue.isEmpty()) {
                        try { diskLock.wait(); } catch (InterruptedException e) {}
                    }
                    req = diskQueue.poll();
                }
                try { Thread.sleep(200); } catch (InterruptedException e) {} 
                switch (req.type) {
                    case LOAD_FROM_PROG:
                        System.out.println("--- DiskManager: LOAD (Programa) P" + req.pcb.getId() + ", Pag " + req.page + " -> Frame " + req.frame + " CONCLUÍDO.");
                        Hardware.Word[] prog = programStore.get(req.pcb.getProgramId());
                        so.utils.loadPage(prog, req.frame, req.page);
                        req.pcb.getPageTable()[req.page].valid = true;
                        req.pcb.getPageTable()[req.page].frameNumber = req.frame;
                        so.processManager.unblockProcess(req.pcb, "Fim_Page_Fault");
                        break;
                    case LOAD_FROM_SWAP:
                        System.out.println("--- DiskManager: LOAD (Swap) P" + req.pcb.getId() + ", Pag " + req.page + " -> Frame " + req.frame + " CONCLUÍDO.");
                        String swapKey = req.pcb.getId() + "_" + req.page;
                        Hardware.Word[] pageData = swapStore.get(swapKey);
                        if (pageData != null) {
                            so.utils.loadPage(pageData, req.frame, 0); 
                            swapStore.remove(swapKey); 
                        }
                        req.pcb.getPageTable()[req.page].valid = true;
                        req.pcb.getPageTable()[req.page].frameNumber = req.frame;
                        req.pcb.getPageTable()[req.page].onDisk = false;
                        so.processManager.unblockProcess(req.pcb, "Fim_Page_Fault");
                        break;
                    case SAVE_TO_SWAP:
                        System.out.println("--- DiskManager: SAVE (Swap) P" + req.pcb.getId() + ", Pag " + req.page + " <- Frame " + req.frame + " CONCLUÍDO.");
                        Hardware.Word[] dataToSave = so.utils.savePage(req.frame);
                        String key = req.pcb.getId() + "_" + req.page;
                        swapStore.put(key, dataToSave);
                        SisOp_GM.FrameInfo info = so.gm.getFrameInfo(req.frame);
                        if (info != null && info.waiter != null) {
                            SisOp_ProcessManager.PCB waiterPcb = info.waiter;
                            int waiterPage = -1;
                            for(int p=0; p < waiterPcb.getPageTable().length; p++) {
                                if (!waiterPcb.getPageTable()[p].valid && !waiterPcb.getPageTable()[p].onDisk) {
                                    waiterPage = p;
                                    break;
                                }
                            }
                            if (waiterPage == -1) waiterPage = 0; 
                            System.out.println("--- DiskManager: Frame " + req.frame + " está livre. Acordando P" + waiterPcb.getId() + " para carregar Pag " + waiterPage);
                            so.gm.occupyFrame(req.frame, waiterPcb, waiterPage);
                            requestLoad(waiterPcb, waiterPage, req.frame);
                        } else {
                            so.gm.freeFrame(req.frame);
                        }
                        break;
                }
            }
        }
    }
    
    // --- CLASSE LOGGER MODIFICADA ---
    public class Logger {
        private PrintWriter logFile;
        private String logFileName;
        // --- NOVO ---
        // Define o formato de alinhamento
        private String logFormat;

        public Logger() {
            try {
                String diretorio = "logs";
                File pasta = new File(diretorio);
                if (!pasta.exists()) {
                    pasta.mkdirs();
                }
                File[] arquivos = pasta.listFiles((dir, nome) -> nome.startsWith("log_") && nome.endsWith(".txt"));
                int proximoNumero = 1;
                if (arquivos != null && arquivos.length > 0) {
                    Arrays.sort(arquivos);
                    String ultimoNome = arquivos[arquivos.length - 1].getName();
                    String numeroStr = ultimoNome.substring(4, 7);
                    proximoNumero = Integer.parseInt(numeroStr) + 1;
                }
                String nomeArquivo = "log_" + new DecimalFormat("000").format(proximoNumero) + ".txt";
                File arquivoLog = new File(pasta, nomeArquivo);
                
                this.logFile = new PrintWriter(new FileWriter(arquivoLog), true);
                this.logFileName = nomeArquivo;
                
                // --- MUDANÇA (LOGGER FORMAT) ---
                // Define as larguras: ID(5), NOME(15), MOTIVO(20), ESTADO_ANT(12), ESTADO_NOVO(12), TABELA
                // %-Xs = String alinhada à esquerda com X caracteres
                this.logFormat = "%-5s %-15s %-20s %-12s %-12s %s";
                
                // Escreve o cabeçalho formatado
                this.logFile.println(String.format(this.logFormat, 
                    "ID", "NOME_PROG", "MOTIVO", "ESTADO_ANT", "ESTADO_NOVO", "TABELA_PAGINAS"
                ));
                System.out.println("Sistema de Log iniciado. Gravando em: " + nomeArquivo);
                
            } catch (IOException e) {
                System.out.println("ERRO CRÍTICO: Não foi possível iniciar o Logger.");
                e.printStackTrace();
            }
        }

        private String formatPageTable(Hardware.PageTableEntry[] table) {
            StringBuilder sb = new StringBuilder("{ ");
            for (int i = 0; i < table.length; i++) {
                Hardware.PageTableEntry entry = table[i];
                if (entry.valid) {
                    // [pag, frame, mp]
                    sb.append(String.format("[%d,%d,mp]", i, entry.frameNumber));
                } else if (entry.onDisk) {
                    // [pag, end_disco, ms]
                    // (Nota: diskAddress não foi implementado, então será -1)
                    sb.append(String.format("[%d,%d,ms]", i, entry.diskAddress));
                } else {
                    // [pag, _, _]
                    sb.append(String.format("[%d,_,_]", i));
                }
                if (i < table.length - 1) {
                    sb.append(", ");
                }
            }
            sb.append(" }");
            return sb.toString();
        }

        public void log(int id, String progName, String reason, String initialState, String nextState, Hardware.PageTableEntry[] pageTable) {
            if (this.logFile == null) return;
            
            String tableStr = formatPageTable(pageTable);
            
            // --- MUDANÇA (LOGGER FORMAT) ---
            // Usa o formato de alinhamento para escrever a linha
            // Converte 'id' para String para corresponder ao formato "%-5s"
            this.logFile.println(String.format(this.logFormat,
                Integer.toString(id), progName, reason, initialState, nextState, tableStr
            ));
        }

        public void close() {
            if (this.logFile != null) {
                System.out.println("Fechando arquivo de log: " + this.logFileName);
                this.logFile.close();
            }
        }
    }
}