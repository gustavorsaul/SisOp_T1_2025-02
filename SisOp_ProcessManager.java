import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

// Gerencia todos os aspectos dos processos.
public class SisOp_ProcessManager {

    public enum ProcessState {
        READY, RUNNING, TERMINATED, BLOCKED
    }

    public static class PCB {
        private int id;
        private int pc;
        private Hardware.PageTableEntry[] pageTable; 
        private int[] registradores;
        private ProcessState state;
        private int programId;
        // --- NOVO CAMPO (LOGGER) ---
        private String programName;

        public PCB(int id, Hardware.PageTableEntry[] pageTable, int programId, String programName) {
            this.id = id;
            this.pc = 0;
            this.pageTable = pageTable;
            this.state = ProcessState.READY;
            this.registradores = new int[10];
            this.programId = programId;
            // --- NOVO CAMPO (LOGGER) ---
            this.programName = programName;
        }

        public int getId() { return id; }
        public void setContext(int pc, int[] regs) { this.pc = pc; this.registradores = Arrays.copyOf(regs, regs.length); }
        public int getPc() { return pc; }
        public Hardware.PageTableEntry[] getPageTable() { return pageTable; }
        public int[] getRegistradores() { return registradores; }
        public ProcessState getState() { return state; }
        public void setState(ProcessState state) { this.state = state; }
        public int getProgramId() { return programId; }
        // --- NOVO MÉTODO (LOGGER) ---
        public String getProgramName() { return programName; }
    }

    private List<PCB> pcbList;
    private Queue<PCB> readyQueue;
    private Queue<PCB> blockedQueue;
    private PCB runningProcess;
    private int nextProcessId;
    private final Object schedulerLock = new Object();

    private SisOp so;

    public SisOp_ProcessManager(SisOp so) {
        this.so = so;
        this.pcbList = new ArrayList<>();
        this.readyQueue = new LinkedList<>();
        this.blockedQueue = new LinkedList<>();
        this.runningProcess = null;
        this.nextProcessId = 1;
    }

    public PCB getRunningProcess() { return runningProcess; }
    public Queue<PCB> getReadyQueue() { return readyQueue; }
    public Queue<PCB> getBlockedQueue() { return blockedQueue; }
    public Object getSchedulerLock() { return schedulerLock; }

    public void execAllBlocking(int quantum) {
        // ... (inalterado) ...
        if (so.getMode() == SisOp.ExecutionMode.THREADED) {
            System.out.println("Comando 'execAll' não está disponível no modo de execução contínua (threaded).");
            return;
        }
        if (readyQueue.isEmpty()) {
            System.out.println("Nenhum processo na fila de prontos para executar.");
            return;
        }
        System.out.println("---------------------------------- Iniciando execução BLOQUEANTE de processos");
        while (runningProcess != null || !readyQueue.isEmpty()) {
            if (runningProcess == null) {
                escalonar(false);
            }
            if (runningProcess != null) {
                so.hw.cpu.step(quantum);
            }
        }
        System.out.println("---------------------------------- Todos os processos terminaram (modo bloqueante).");
    }

    // --- MUDANÇA (LOGGER) ---
    // Assinatura alterada para incluir progName
    public int criaProcesso(Hardware.Word[] programa, String progName) {
        synchronized (schedulerLock) {
            if (programa == null) {
                System.out.println("Erro: Programa não encontrado.");
                return -1;
            }
            
            Hardware.PageTableEntry[] tabelaPaginas = so.gm.createPageTable(programa.length);

            // --- MUDANÇA (LOGGER) ---
            // Construtor do PCB atualizado
            PCB pcb = new PCB(nextProcessId++, tabelaPaginas, -1, progName); 
            pcb.programId = pcb.getId(); 

            so.diskManager.saveProgramToStore(pcb.getProgramId(), programa);

            int frame = so.gm.findFreeFrame();
            if (frame == -1) {
                System.out.println("Erro: Memória insuficiente para carregar a página 0.");
                return -1;
            }

            so.gm.occupyFrame(frame, pcb, 0);
            tabelaPaginas[0].valid = true;
            tabelaPaginas[0].frameNumber = frame;

            so.utils.loadPage(programa, frame, 0);

            pcbList.add(pcb);
            readyQueue.add(pcb);
            System.out.println("Processo " + pcb.getId() + " ("+progName+") criado. Página 0 carregada no frame " + frame + ".");

            // --- MUDANÇA (LOGGER) ---
            // Loga a criação do processo
            so.logger.log(pcb.getId(), pcb.getProgramName(), "criacao", "NULO", "PRONTO", pcb.getPageTable());

            if (so.getMode() == SisOp.ExecutionMode.THREADED) {
                schedulerLock.notifyAll();
            }
            return pcb.getId();
        }
    }

    public void desalocaProcesso(int id) {
        // ... (inalterado) ...
        synchronized (schedulerLock) {
            PCB pcb = findPcbById(id);
            if (pcb == null) {
                System.out.println("Erro: Processo com ID " + id + " não encontrado.");
                return;
            }
            so.gm.desaloca(pcb); 
            so.diskManager.clearSwap(pcb.getId()); 
            
            pcbList.remove(pcb);
            readyQueue.remove(pcb);
            blockedQueue.remove(pcb); 
            if (runningProcess != null && runningProcess.getId() == id) {
                runningProcess = null;
                terminaProcessoAtual();
            }
            System.out.println("Processo " + id + " desalocado.");
        }
    }

    private PCB findPcbById(int id) {
        // ... (inalterado) ...
        for (PCB pcb : pcbList)
            if (pcb.getId() == id)
                return pcb;
        return null;
    }

    public void escalonar(boolean processoTerminou) {
        synchronized (schedulerLock) {
            if (runningProcess != null && !processoTerminou) {
                PCB preemptedPcb = runningProcess; // Pega o PCB antes de salvar
                runningProcess.setContext(so.hw.cpu.getContextPC(), so.hw.cpu.getContextRegs());
                runningProcess.setState(ProcessState.READY);
                readyQueue.add(runningProcess);
                System.out.println("Processo " + runningProcess.getId() + " salvo (quantum) e movido para a fila de prontos.\n");
                
                // --- MUDANÇA (LOGGER) ---
                // Loga a preempção por quantum
                so.logger.log(preemptedPcb.getId(), preemptedPcb.getProgramName(), "fatia_tempo", "EXECUTANDO", "PRONTO", preemptedPcb.getPageTable());
            }

            if (readyQueue.isEmpty()) {
                runningProcess = null;
                so.hw.cpu.stop(); 
                if (so.getMode() == SisOp.ExecutionMode.BLOCKING) {
                    System.out.println("---------------------------------- Fila de prontos vazia. Fim do 'execAll'.");
                } else {
                    System.out.println("---------------------------------- Fila de prontos vazia. CPU em espera.");
                }
                return;
            }

            runningProcess = readyQueue.poll();
            runningProcess.setState(ProcessState.RUNNING);
            
            // --- MUDANÇA (LOGGER) ---
            // Loga o dispatch (escalonamento)
            so.logger.log(runningProcess.getId(), runningProcess.getProgramName(), "escalonador", "PRONTO", "EXECUTANDO", runningProcess.getPageTable());
            
            so.hw.cpu.setContext(runningProcess.getPc(), runningProcess.getRegistradores());
            so.hw.cpu.setMMU(runningProcess.getPageTable()); 
            so.hw.cpu.resetInstructionCounter();
            so.hw.cpu.start(); 
            
            System.out.println(">>> Assumindo CPU: Processo " + runningProcess.getId());
        }
    }

    public void terminaProcessoAtual() {
        synchronized (schedulerLock) {
            if (runningProcess == null)
                return;

            PCB terminatedPcb = runningProcess; // Pega o PCB antes de anular
            System.out.println("Processo " + terminatedPcb.getId() + " terminou.");
            terminatedPcb.setState(ProcessState.TERMINATED);
            
            // --- MUDANÇA (LOGGER) ---
            // Loga a finalização
            so.logger.log(terminatedPcb.getId(), terminatedPcb.getProgramName(), "finalizacao", "EXECUTANDO", "TERMINADO", terminatedPcb.getPageTable());

            so.gm.desaloca(terminatedPcb); 
            so.diskManager.clearSwap(terminatedPcb.getId()); 
            
            pcbList.remove(terminatedPcb);
            runningProcess = null;
            escalonar(true); 
        }
    }

    // --- MUDANÇA (LOGGER) ---
    // Assinatura alterada para incluir "reason"
    public void blockCurrentProcess(String reason) {
        synchronized (schedulerLock) {
            if (runningProcess == null) return;
            
            PCB pcb = runningProcess; // Pega o PCB

            // --- MUDANÇA (LOGGER) ---
            // Loga o bloqueio
            so.logger.log(pcb.getId(), pcb.getProgramName(), reason, "EXECUTANDO", "BLOQUEADO", pcb.getPageTable());
            
            pcb.setState(ProcessState.BLOCKED);
            blockedQueue.add(pcb);

            System.out.println("Processo " + pcb.getId() + " BLOQUEADO. Motivo: " + reason);

            runningProcess = null;
            escalonar(true);
        }
    }

    // --- MUDANÇA (LOGGER) ---
    // Assinatura alterada para incluir "reason"
    public void unblockProcess(PCB pcb, String reason) {
        synchronized (schedulerLock) {
            if (pcb == null) return;
            
            // --- MUDANÇA (LOGGER) ---
            // Loga o desbloqueio
            so.logger.log(pcb.getId(), pcb.getProgramName(), reason, "BLOQUEADO", "PRONTO", pcb.getPageTable());

            blockedQueue.remove(pcb);
            pcb.setState(ProcessState.READY);
            readyQueue.add(pcb);
            
            System.out.println("Processo " + pcb.getId() + " DESBLOQUEADO. Motivo: " + reason);

            if (so.getMode() == SisOp.ExecutionMode.THREADED) {
                schedulerLock.notifyAll();
            }
        }
    }

    public void listAllProcesses() {
        // ... (inalterado) ...
        synchronized (schedulerLock) {
            System.out.println("Lista de todos os processos:");
            if (pcbList.isEmpty()) {
                System.out.println("Nenhum processo no sistema.");
                return;
            }
            for (PCB pcb : pcbList) {
                System.out.println("  ID: " + pcb.getId() + ", Nome: " + pcb.getProgramName() + ", Estado: " + pcb.getState() + ", PC: " + pcb.getPc());
            }
        }
    }

    public void dumpProcess(int id) {
        // ... (inalterado) ...
        synchronized (schedulerLock) {
            PCB pcb = findPcbById(id);
            if (pcb == null) {
                System.out.println("Erro: Processo com ID " + id + " não encontrado.");
                return;
            }
            System.out.println("--- Dump do Processo ID: " + pcb.getId() + " (" + pcb.getProgramName() + ") ---");
            System.out.println("  Estado: " + pcb.getState() + ", PC Lógico: " + pcb.getPc());
            System.out.println("  Tabela de Páginas (Total: " + pcb.getPageTable().length + " páginas):");
            int i = 0;
            for (Hardware.PageTableEntry entry : pcb.getPageTable()) {
                String status;
                if (entry.valid) {
                    status = "Válida (Frame " + entry.frameNumber + ")";
                } else if (entry.onDisk) {
                    status = "Em Disco (Endereço " + entry.diskAddress + ")";
                } else {
                    status = "Inválida (Nunca carregada)";
                }
                System.out.println("    Página " + i + ": " + status);
                i++;
            }
            System.out.println("--- Fim do Dump ---");
        }
    }
}