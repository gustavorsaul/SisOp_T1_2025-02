import java.util.Arrays;

// Interface para o Gerente de Memória.
interface GM_Interface {
    // Métodos antigos removidos/alterados
}

// Implementação com gerenciamento de frames para VM
public class SisOp_GM implements GM_Interface {
    private int tamPg;
    private int qtdFrames;
    private FrameInfo[] frameMap; // Mapa de frames físicos

    // --- NOVA CLASSE (PASSO 1) ---
    // Mapeia o que está em cada frame físico
    public class FrameInfo {
        public SisOp_ProcessManager.PCB pcb;
        public int pageNumber; // Página lógica do processo
        public SisOp_ProcessManager.PCB waiter; // Processo esperando este frame (vitimização)

        public FrameInfo(SisOp_ProcessManager.PCB pcb, int pageNumber) {
            this.pcb = pcb;
            this.pageNumber = pageNumber;
            this.waiter = null;
        }
    }

    public SisOp_GM(int tamMem, int tamPg) {
        this.tamPg = tamPg;
        this.qtdFrames = tamMem / tamPg;
        this.frameMap = new FrameInfo[qtdFrames];
        // Não preenchemos mais 'livre', 'null' significa livre
    }

    // --- NOVO MÉTODO (PASSO 1 e 5) ---
    // Apenas cria a estrutura da tabela de páginas, não aloca frames
    public Hardware.PageTableEntry[] createPageTable(int nroPalavras) {
        int paginasNec = (nroPalavras + this.tamPg - 1) / this.tamPg;
        if (paginasNec == 0) paginasNec = 1; // Processo deve ter pelo menos 1 página
        
        Hardware.PageTableEntry[] table = new Hardware.PageTableEntry[paginasNec];
        for (int i = 0; i < paginasNec; i++) {
            table[i] = new Hardware.PageTableEntry();
        }
        return table;
    }

    // --- MÉTODO ATUALIZADO (PASSO 1) ---
    // Desaloca frames com base no PCB que está terminando
    public void desaloca(SisOp_ProcessManager.PCB pcb) {
        if (pcb == null) return;
        for (int i = 0; i < qtdFrames; i++) {
            if (frameMap[i] != null && frameMap[i].pcb.getId() == pcb.getId()) {
                frameMap[i] = null; // Libera o frame
            }
        }
    }
    
    // --- NOVOS MÉTODOS (PASSO 4) ---

    // Encontra um frame livre (null)
    public int findFreeFrame() {
        for (int i = 0; i < qtdFrames; i++) {
            if (frameMap[i] == null) {
                return i;
            }
        }
        return -1; // Nenhum frame livre
    }

    // Política de vitimização simples: FIFO (pega o primeiro ocupado, ignora frame 0)
    public int selectVictimFrame() {
        // Começa do 1 para "proteger" o frame 0 (muito simples, mas ok)
        for (int i = 1; i < qtdFrames; i++) {
            if (frameMap[i] != null && frameMap[i].waiter == null) {
                return i; // Encontrou uma vítima
            }
        }
        // Se todos (menos o 0) estão esperando, pega o 0
        if (frameMap[0] != null && frameMap[0].waiter == null) return 0;
        
        return 1; // Fallback (não deve acontecer se houver > 1 frame)
    }

    // Pega as informações de um frame (para a vitimização)
    public FrameInfo getFrameInfo(int frame) {
        if (frame < 0 || frame >= qtdFrames) return null;
        return frameMap[frame];
    }
    
    // Marca um frame como ocupado
    public void occupyFrame(int frame, SisOp_ProcessManager.PCB pcb, int page) {
        if (frame < 0 || frame >= qtdFrames) return;
        frameMap[frame] = new FrameInfo(pcb, page);
    }
    
    // Libera um frame
    public void freeFrame(int frame) {
        if (frame < 0 || frame >= qtdFrames) return;
        frameMap[frame] = null;
    }
    
    // Define um processo como "esperando" por um frame (para vitimização)
    public void setWaiter(int frame, SisOp_ProcessManager.PCB waiterPcb) {
        if (frame < 0 || frame >= qtdFrames) return;
        if (frameMap[frame] != null) {
            frameMap[frame].waiter = waiterPcb;
        }
    }
}