import java.util.Arrays;

interface GM_Interface {
}

public class SisOp_GM implements GM_Interface {
    private int tamPg;
    private int qtdFrames;
    private FrameInfo[] frameMap;

    public class FrameInfo {
        public SisOp_ProcessManager.PCB pcb;
        public int pageNumber;
        public SisOp_ProcessManager.PCB waiter;
        public int waiterPage = -1; 

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
    }

    public Hardware.PageTableEntry[] createPageTable(int nroPalavras) {
        int paginasNec = (nroPalavras + this.tamPg - 1) / this.tamPg;
        if (paginasNec == 0) paginasNec = 1; 
        
        Hardware.PageTableEntry[] table = new Hardware.PageTableEntry[paginasNec];
        for (int i = 0; i < paginasNec; i++) {
            table[i] = new Hardware.PageTableEntry();
        }
        return table;
    }

    public void desaloca(SisOp_ProcessManager.PCB pcb) {
        if (pcb == null) return;
        for (int i = 0; i < qtdFrames; i++) {
            if (frameMap[i] != null && frameMap[i].pcb.getId() == pcb.getId()) {
                frameMap[i] = null; 
            }
        }
    }
    
    public int findFreeFrame() {
        for (int i = 0; i < qtdFrames; i++) {
            if (frameMap[i] == null) {
                return i;
            }
        }
        return -1; 
    }

    public int selectVictimFrame() {
        for (int i = 0; i < qtdFrames; i++) {
            if (frameMap[i] != null && frameMap[i].waiter == null) {
                return i; 
            }
        }
        
        return 0; 
    }

    public FrameInfo getFrameInfo(int frame) {
        if (frame < 0 || frame >= qtdFrames) return null;
        return frameMap[frame];
    }
    
    public void occupyFrame(int frame, SisOp_ProcessManager.PCB pcb, int page) {
        if (frame < 0 || frame >= qtdFrames) return;
        frameMap[frame] = new FrameInfo(pcb, page);
    }
    
    public void freeFrame(int frame) {
        if (frame < 0 || frame >= qtdFrames) return;
        frameMap[frame] = null;
    }
    
    public void setWaiter(int frame, SisOp_ProcessManager.PCB waiterPcb, int pageNeeded) {
        if (frame < 0 || frame >= qtdFrames) return;
        if (frameMap[frame] != null) {
            frameMap[frame].waiter = waiterPcb;
            frameMap[frame].waiterPage = pageNeeded;
        }
    }
}