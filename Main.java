package wizards;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) {
        // Verifica se o caminho do arquivo de teste foi fornecido
        String inputFile;
        if (args.length != 1) {
            System.err.println("Uso: java -jar target/efoliob-1.0-jar-with-dependencies.jar <caminho_para_arquivo_de_teste>");
            System.exit(1);
        }
        inputFile = args[0];

        TACGenerator tac = new TACGenerator();
        TACOptimizer optimizer = new TACOptimizer();
        try {
            //printTokens(inputFile);
            printFile(new FileInputStream(inputFile));
            tac.generateTACFromSource(new FileInputStream(inputFile));
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println("Código Intermédio (Three Address Code):");

        tac.getFunctionsTAC().forEach((fn, localTAC) -> {
            System.out.println("Função " + fn + ":");
            localTAC.forEach(code -> System.out.println(code));
        });
        tac.getGlobalTAC().forEach(code -> System.out.println(code));

        System.out.println("\nCódigo Intermédio Otimizado:");
        List<String> optimizedTAC = optimizer.optimizeTAC(tac.getGlobalTAC());
        optimizedTAC.forEach(System.out::println);
        for (var entry : tac.getFunctionsTAC().entrySet()) {
            System.out.println("Função " + entry.getKey() + " (otimizada):");
            optimizedTAC = optimizer.optimizeTAC(entry.getValue());
            optimizedTAC.forEach(System.out::println);
        }

        // Converter TAC para Assembly
        TACToAssemblyConverter converter = new TACToAssemblyConverter();
        List<String> assemblyCode = converter.convert(tac.getFunctionsTAC(), tac.getGlobalTAC());

        // Imprimir código Assembly gerado
        System.out.println("\nCódigo Assembly Gerado:");
        assemblyCode.forEach(System.out::println);
    }

    // Método para imprimir os tokens do arquivo
    public static void printFile(InputStream inputFile) throws IOException {
        System.out.println("=== SOURCE CODE ===");
        // Lê o arquivo de teste e imprime o seu conteúdo
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
            }
        }
        System.out.println("=== END SOURCE CODE ===\n");
    }
}
