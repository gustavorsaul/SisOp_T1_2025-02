import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

// Gerencia todos os aspectos dos processos.
public class SisOp_ProcessManager {

    public enum ProcessState {
        READY, RUNNING, TERMINATED,
        // PASSO 1: Adicionado estado de bloqueio
        BLOCKED
    }

    public static class PCB {
        private int id;
        private int pc;
        private int[] pageTable;
        private int[] registradores;
        private ProcessState state;

        public PCB(int id, int[] pageTable) {
            this.id = id;
            this.pc = 0;
            this.pageTable = pageTable;
            this.state = ProcessState.READY;
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

    private List<PCB> pcbList;
    private Queue<PCB> readyQueue;
    // PASSO 1: Fila para processos bloqueados
    private Queue<PCB> blockedQueue;
    private PCB runningProcess;
    private int nextProcessId;
    private final Object schedulerLock = new Object();

    private SisOp so;

    public SisOp_ProcessManager(SisOp so) {
        this.so = so;
        this.pcbList = new ArrayList<>();
        this.readyQueue = new LinkedList<>();
        // PASSO 1: Inicializa a fila de bloqueados
        this.blockedQueue = new LinkedList<>();
        this.runningProcess = null;
        this.nextProcessId = 1;
    }

    public PCB getRunningProcess() { return runningProcess; }
    public Queue<PCB> getReadyQueue() { return readyQueue; }
    public Queue<PCB> getBlockedQueue() { return blockedQueue; }
    public Object getSchedulerLock() { return schedulerLock; }

    public void execAllBlocking(int quantum) {
        if (so.getMode() == SisOp.ExecutionMode.THREADED) {
            System.out.println("Comando 'execAll' não está disponível no modo de execução contínua (threaded).");
            return;
        }
        if (readyQueue.isEmpty()) {
            System.out.println("Nenhum processo na fila de prontos para executar.");
            return;
        }
        System.out.println("---------------------------------- Iniciando execução BLOQUEANTE de processos");
        // Loop de execução bloqueante
        while (runningProcess != null || !readyQueue.isEmpty()) {
            if (runningProcess == null) {
                escalonar(false);
            }
            if (runningProcess != null) {
                so.hw.cpu.step(quantum);
            }
            // NOTA: Em modo bloqueante, a E/S será efetivamente bloqueante 
            // pois não há outra thread (DeviceManager) para processá-la.
            // O modo 'thread2' é necessário para a concorrência.
        }
        System.out.println("---------------------------------- Todos os processos terminaram (modo bloqueante).");
    }

    public int criaProcesso(Hardware.Word[] programa) {
        synchronized (schedulerLock) {
            if (programa == null) {
                System.out.println("Erro: Programa não encontrado.");
                return -1;
            }
            int[] tabelaPaginas = so.gm.aloca(programa.length);
            if (tabelaPaginas == null) {
                System.out.println("Erro: Falha de alocação de memória.");
                return -1;
            }
            PCB pcb = new PCB(nextProcessId++, tabelaPaginas);
            so.utils.loadProgram(programa, pcb.getPageTable(), so.TAM_PAG);
            pcbList.add(pcb);
            readyQueue.add(pcb);
            System.out.println("Processo " + pcb.getId() + " criado e pronto.");
            if (so.getMode() == SisOp.ExecutionMode.THREADED) {
                schedulerLock.notifyAll();
            }
            return pcb.getId();
        }
    }

    public void desalocaProcesso(int id) {
        synchronized (schedulerLock) {
            PCB pcb = findPcbById(id);
            if (pcb == null) {
                System.out.println("Erro: Processo com ID " + id + " não encontrado.");
                return;
            }
            so.gm.desaloca(pcb.getPageTable());
            pcbList.remove(pcb);
            readyQueue.remove(pcb);
            // Atualizado para remover da fila de bloqueados também
            blockedQueue.remove(pcb); 
            if (runningProcess != null && runningProcess.getId() == id) {
                runningProcess = null;
                terminaProcessoAtual();
            }
            System.out.println("Processo " + id + " desalocado.");
        }
    }

    private PCB findPcbById(int id) {
        for (PCB pcb : pcbList)
            if (pcb.getId() == id)
                return pcb;
        return null;
    }

    public void escalonar(boolean processoTerminou) {
        synchronized (schedulerLock) {
            if (runningProcess != null && !processoTerminou) {
                // Processo não terminou, foi preempção (quantum)
                runningProcess.setContext(so.hw.cpu.getContextPC(), so.hw.cpu.getContextRegs());
                runningProcess.setState(ProcessState.READY);
                readyQueue.add(runningProcess);
                System.out.println("Processo " + runningProcess.getId() + " salvo (quantum) e movido para a fila de prontos.\n");
            }

            if (readyQueue.isEmpty()) {
                runningProcess = null;
                so.hw.cpu.stop(); // Para a CPU se não houver ninguém pronto
                if (so.getMode() == SisOp.ExecutionMode.BLOCKING) {
                    System.out.println("---------------------------------- Fila de prontos vazia. Fim do 'execAll'.");
                } else {
                    // No modo threaded, a CPU espera (o escalonador vai para 'wait')
                    System.out.println("---------------------------------- Fila de prontos vazia. CPU em espera.");
                }
                return;
            }

            // Pega o próximo processo da fila de prontos
            runningProcess = readyQueue.poll();
            runningProcess.setState(ProcessState.RUNNING);
            
            // Restaura o contexto do processo na CPU
            so.hw.cpu.setContext(runningProcess.getPc(), runningProcess.getRegistradores());
            so.hw.cpu.setMMU(runningProcess.getPageTable());
            so.hw.cpu.resetInstructionCounter();
            so.hw.cpu.start(); // Liga a CPU para executar
            
            System.out.println(">>> Assumindo CPU: Processo " + runningProcess.getId());
        }
    }

    public void terminaProcessoAtual() {
        synchronized (schedulerLock) {
            if (runningProcess == null)
                return;
            System.out.println("Processo " + runningProcess.getId() + " terminou.");
            runningProcess.setState(ProcessState.TERMINATED);
            so.gm.desaloca(runningProcess.getPageTable());
            pcbList.remove(runningProcess);
            runningProcess = null;
            // Escalonar, sinalizando que o processo terminou (não volta pra fila)
            escalonar(true); 
        }
    }

    // PASSO 3: Método chamado pela SYSCALL de E/S
    public void blockCurrentProcess() {
        synchronized (schedulerLock) {
            if (runningProcess == null) return;

            // NOTA: O contexto JÁ foi salvo no SysCallHandling.handle() 
            // antes de chamar este método, então NÃO salvamos novamente aqui
            // para evitar sobrescrever o PC já avançado
            
            // 1. Muda o estado para BLOQUEADO
            runningProcess.setState(ProcessState.BLOCKED);
            // 2. Adiciona na fila de bloqueados
            blockedQueue.add(runningProcess);

            System.out.println("Processo " + runningProcess.getId() + " BLOQUEADO esperando E/S.");

            // 3. Libera a CPU
            runningProcess = null;
            // 4. Chama o escalonador (como se o processo tivesse terminado)
            escalonar(true);
        }
    }

    // PASSO 4: Método chamado pela Interrupção de E/S
    public void unblockProcess(PCB pcb) {
        synchronized (schedulerLock) {
            if (pcb == null) return;
            
            // 1. Remove da fila de bloqueados
            blockedQueue.remove(pcb);
            // 2. Muda o estado para PRONTO
            pcb.setState(ProcessState.READY);
            // 3. Adiciona na fila de prontos
            readyQueue.add(pcb);
            
            System.out.println("Processo " + pcb.getId() + " DESBLOQUEADO. E/S concluída.");

            // 4. Acorda a thread do escalonador (SchedulerExecutor) se ela estiver dormindo
            if (so.getMode() == SisOp.ExecutionMode.THREADED) {
                schedulerLock.notifyAll();
            }
        }
    }

    public void listAllProcesses() {
        synchronized (schedulerLock) {
            System.out.println("Lista de todos os processos:");
            if (pcbList.isEmpty()) {
                System.out.println("Nenhum processo no sistema.");
                return;
            }
            for (PCB pcb : pcbList) {
                System.out.println("  ID: " + pcb.getId() + ", Estado: " + pcb.getState() + ", PC: " + pcb.getPc() + ", Tabela: " + Arrays.toString(pcb.getPageTable()));
            }
        }
    }

    public void dumpProcess(int id) {
        synchronized (schedulerLock) {
            PCB pcb = findPcbById(id);
            if (pcb == null) {
                System.out.println("Erro: Processo com ID " + id + " não encontrado.");
                return;
            }
            System.out.println("--- Dump do Processo ID: " + pcb.getId() + " ---");
            System.out.println("  Estado: " + pcb.getState() + ", PC Lógico: " + pcb.getPc());
            System.out.println("  Tabela de Páginas: " + Arrays.toString(pcb.getPageTable()));
            System.out.println("  Conteúdo da Memória (visão física):");
            for (int frame : pcb.getPageTable()) {
                int start = frame * so.TAM_PAG;
                int end = start + so.TAM_PAG;
                System.out.println("    Frame " + frame + " (Endereços Físicos " + start + "-" + (end - 1) + "):");
                so.utils.dump(start, end);
            }
            System.out.println("--- Fim do Dump ---");
        }
    }
}
