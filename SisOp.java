import java.util.LinkedList;
import java.util.Queue;
// Removido: import java.util.Scanner;

// Classe principal do Sistema Operacional. Agrega e inicializa todos os módulos.
public class SisOp {
    
    public Hardware.HW hw;
    public InterruptHandling interruptHandling;
    public SysCallHandling sysCallHandling;
    public Utilities utils;
    public SisOp_GM gm;
    public SisOp_ProcessManager processManager;
    public DeviceManager deviceManager;
    
    // Armazena a referência ao Shell (host)
    private Sistema sistemaHost; 

    public final int TAM_PAG = 16;

    public enum ExecutionMode { BLOCKING, THREADED }
    private ExecutionMode mode = ExecutionMode.BLOCKING;
    private boolean schedulerThreadStarted = false;

    // Construtor agora recebe o Sistema (host)
    public SisOp(Hardware.HW hw, Sistema host) {
        this.hw = hw;
        this.sistemaHost = host; // Salva a referência
        
        // Inicializa todos os módulos do SO
        this.interruptHandling = new InterruptHandling(this);
        this.sysCallHandling = new SysCallHandling(this);
        this.hw.cpu.setAddressOfHandlers(this.interruptHandling, this.sysCallHandling);
        this.gm = new SisOp_GM(hw.mem.pos.length, TAM_PAG);
        this.utils = new Utilities(hw);
        this.hw.cpu.setUtilities(this.utils);
        this.processManager = new SisOp_ProcessManager(this);
        
        // O DeviceManager agora pode obter a referência do host a partir do 'this'
        this.deviceManager = new DeviceManager(this);
        new Thread(this.deviceManager).start();
    }
    
    public ExecutionMode getMode() {
        return this.mode;
    }

    // Método para o DeviceManager acessar o host
    public Sistema getSistemaHost() {
        return this.sistemaHost;
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
        // ... (Classe Utilities permanece inalterada) ...
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
        // ... (Classe InterruptHandling permanece inalterada desde a última vez) ...
        private SisOp so;
        private SisOp_ProcessManager.PCB lastIOProcess; 

        public InterruptHandling(SisOp so) { this.so = so; }

        public void setLastIOProcess(SisOp_ProcessManager.PCB pcb) {
            this.lastIOProcess = pcb;
        }

        public void handle(Hardware.CPU.Interrupts irpt) {
            switch (irpt) {
                case intQuantumEnd:
                    System.out.println("\n\nInterrupcao de TEMPO");
                    so.processManager.escalonar(false);
                    break;
                
                case intIO:
                    // Esta interrupção não é mais usada para E/S do console,
                    // mas pode ser usada no futuro para outros dispositivos.
                    System.out.println("\n\nInterrupcao de E/S (Dispositivo Concluiu)");
                    if (lastIOProcess != null) {
                        so.processManager.unblockProcess(lastIOProcess);
                        lastIOProcess = null;
                    } else {
                        System.out.println("ERRO: Interrupcao de E/S sem processo definido.");
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
        // ... (Classe SysCallHandling permanece inalterada) ...
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
                // IMPORTANTE: Salva o contexto ANTES de criar a requisição e bloquear
                // para garantir que o PC já esteja atualizado (avançado pela instrução SYSCALL)
                pcb.setContext(so.hw.cpu.getContextPC(), so.hw.cpu.getContextRegs());
                
                IORequest req = new IORequest(pcb, op, addr);
                so.deviceManager.addRequest(req);
                so.processManager.blockCurrentProcess();
            } else {
                System.out.println("SYSCALL: Operação " + op + " desconhecida.");
            }
        }
    }

    public class IORequest {
        // ... (Classe IORequest permanece inalterada) ...
        public SisOp_ProcessManager.PCB pcb;
        public int operation; // 1 = READ, 2 = WRITE
        public int address;

        public IORequest(SisOp_ProcessManager.PCB pcb, int operation, int address) {
            this.pcb = pcb;
            this.operation = operation;
            this.address = address;
        }
    }

    // Classe DeviceManager (MODIFICADA)
    public class DeviceManager implements Runnable {
        private SisOp so;
        private Queue<IORequest> requestQueue;
        private final Object ioQueueLock = new Object(); // Trava para a fila de requisições
        // REMOVIDO: private Scanner ioScanner; 

        public DeviceManager(SisOp so) {
            this.so = so;
            this.requestQueue = new LinkedList<>();
            // REMOVIDO: this.ioScanner = new Scanner(System.in);
        }

        public void addRequest(IORequest request) {
            synchronized (ioQueueLock) {
                requestQueue.add(request);
                ioQueueLock.notify(); // Acorda a thread do dispositivo
            }
        }

        @Override
        public void run() {
            while (true) {
                IORequest currentRequest;
                
                // 1. Espera por uma requisição na fila
                synchronized (ioQueueLock) {
                    while (requestQueue.isEmpty()) {
                        try {
                            ioQueueLock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    currentRequest = requestQueue.poll();
                }

                // 2. Processa a requisição (Simula a E/S)
                System.out.println("--- Dispositivo de E/S: Iniciando operação " + 
                                   (currentRequest.operation == 1 ? "READ" : "WRITE") + 
                                   " para o Processo " + currentRequest.pcb.getId() + " ---");
                
                try {
                    Thread.sleep(1000); // Simula 1 segundo de E/S
                } catch (InterruptedException e) {} 

                if (currentRequest.operation == 1) { // READ
                    try {
                        // Pega o host (Sistema) e o lock dele
                        Sistema host = so.getSistemaHost();
                        Object hostLock = host.getIoConsoleLock();
                        
                        // 1. Avisa o host (Sistema) que está esperando
                        host.startWaitingForIO(currentRequest.pcb.getId());
                        
                        // 2. Dorme (wait) no lock do host, liberando o host para pegar o input
                        synchronized (hostLock) {
                            hostLock.wait();
                        }

                        // 3. Foi acordado! Pega o input que o host deixou no buffer
                        String input = host.getIoInputBuffer();
                        if (input == null || input.trim().isEmpty()) {
                            System.out.println("--- Dispositivo de E/S: AVISO! Buffer vazio. Usando valor 0. ---");
                            input = "0";
                        }
                        
                        int valor = 0;
                        
                        // 4. Converte e trata erro se não for número
                        try {
                            valor = Integer.parseInt(input.trim());
                        } catch (NumberFormatException e) {
                            System.out.println("--- Dispositivo de E/S: ERRO! Entrada '" + input + "' não é um número. Usando valor 0. ---");
                            valor = 0;
                        }

                        // 5. Escreve na memória (DMA)
                        int endFis = so.hw.cpu.toPhysical(currentRequest.address);
                        if (endFis >= 0) {
                            so.hw.mem.pos[endFis].p = valor;
                            System.out.println("--- Dispositivo de E/S: Valor " + valor + " escrito no endereço lógico " + currentRequest.address + " (físico " + endFis + "). ---");
                        } else {
                            System.out.println("--- Dispositivo de E/S: ERRO! Endereço lógico " + currentRequest.address + " inválido. ---");
                        }
                    } catch (InterruptedException e) {
                        System.out.println("--- Dispositivo de E/S: Thread interrompida durante a espera. ---");
                    }
                    
                } else if (currentRequest.operation == 2) { // WRITE
                    int endFis = so.hw.cpu.toPhysical(currentRequest.address);
                    int valor = -1;
                    if (endFis >= 0) {
                        valor = so.hw.mem.pos[endFis].p;
                    }
                    System.out.println("\n>>> Dispositivo de E/S (OUT do Processo " + currentRequest.pcb.getId() + "): " + valor);
                    System.out.println("--- Dispositivo de E/S: Escrita concluída. ---");
                }

                // 3. E/S Concluída. Desbloqueia o processo DIRETAMENTE
                so.processManager.unblockProcess(currentRequest.pcb);
            }
        }
    }
}