import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Interface para o Gerente de Memória.
interface GM_Interface {
    int[] aloca(int nroPalavras);
    void desaloca(int[] tabelaPaginas);
}

// Implementação simples de um Gerente de Memória com paginação.
public class SisOp_GM implements GM_Interface {
    private int tamPg;
    private int qtdFrames;
    private boolean[] livre;

    public SisOp_GM(int tamMem, int tamPg) {
        this.tamPg = tamPg;
        this.qtdFrames = tamMem / tamPg;
        this.livre = new boolean[qtdFrames];
        Arrays.fill(livre, true);
    }

    @Override
    public int[] aloca(int nroPalavras) {
        int paginasNec = (nroPalavras + this.tamPg - 1) / this.tamPg;
        List<Integer> frames = new ArrayList<>();
        for (int f = 0; f < qtdFrames && frames.size() < paginasNec; f++) {
            if (livre[f]) {
                frames.add(f);
            }
        }
        if (frames.size() < paginasNec) {
            return null; // Memória insuficiente
        }
        int[] tabela = new int[paginasNec];
        for (int i = 0; i < paginasNec; i++) {
            int f = frames.get(i);
            tabela[i] = f;
            livre[f] = false;
        }
        return tabela;
    }

    @Override
    public void desaloca(int[] tabelaPaginas) {
        if (tabelaPaginas == null) return;
        for (int f : tabelaPaginas) {
            if (f >= 0 && f < livre.length) {
                livre[f] = true;
            }
        }
    }
}