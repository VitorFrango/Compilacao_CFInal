package wizards;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;

public class TACOptimizer {

    private Map<String, Integer> label_locations;
    private final Pattern label = Pattern.compile("([a-zA-Z_][a-zA-Z_0-9]*):");
    private final Pattern goto_pattern = Pattern.compile("goto ([a-zA-Z_][a-zA-Z_0-9]*)");
    private List<CFGNode> CFG;
    private Set<String> usage;
    private final List<String> IGNORE_INSTRUCTIONS = List.of("begin_function", "end_function", "goto", "print");
    private final List<String> CONVERSION_OP = List.of("to_int", "to_float");
    private final List<String> BOOLEANS = List.of("true", "false");

    public void mapLabelLocations(List<String> tac) {
        label_locations = new HashMap<>();
        for (int i = 0; i < tac.size(); i++) {
            Matcher match = label.matcher(tac.get(i));
            if (match.find()) {
                label_locations.put(match.group(1), i);
            }
        }
    }

    // Método para otimizar o código intermédio
    public List<String> optimizeTAC(List<String> tac) {
        mapLabelLocations(tac);
        CFG = new ArrayList<>();

        CFGNode root = new CFGNode(0);
        CFG.add(root);
        visitNode(root, tac, 0);

        doOptimize();
        return joinCFG();
    }

    // Método para otimizar o código intermédio
    private void doOptimize() {
        boolean repeat = true;
        while (repeat) {
            usage = new HashSet<>();
            calculateConstants();
            eliminateUnusedVariables();
            repeat &= cutUnreachableBranches();
            Collections.sort(CFG);
        }
    }

    // Método para juntar os nós do grafo de fluxo de controlo
    private List<String> joinCFG() {
        List<String> newTAC = new ArrayList<>();

        Collections.sort(CFG);
        for (CFGNode node : CFG) {
            newTAC.addAll(node.nodeTAC);
        }

        return newTAC;
    }

    private void foundUsage(String variable) {
        usage.add(variable);
    }

    // Método para calcular as constantes do código intermédio
    private void calculateConstants() {
        for (CFGNode node : CFG) {
            Map<String, Number> constants = new HashMap<>();
            for (int i = 0; i < node.nodeTAC.size(); i++) {
                String instruction = node.nodeTAC.get(i);
                if (!instruction.startsWith("\t"))
                    continue;
                String[] elements = instruction.trim().split(" = | ");
                if (IGNORE_INSTRUCTIONS.contains(elements[0]) || elements.length == 1)
                    continue;
                // List size only for variable usage
                if (elements[1].equals("list_size")) {
                    foundUsage(elements[2]);
                }
                // Funções de lista
                else if (elements[0].startsWith("list") && !instruction.contains("=")) {
                    // List variable is used
                    foundUsage(elements[1]);
                    Number pos = getConstant(elements[2], constants);
                    if (pos != null) {
                        elements[2] = pos.toString();
                    } else {
                        foundUsage(elements[2]);
                    }
                    if (elements[0].equals("list_add")) {
                        Number value = getConstant(elements[3], constants);
                        if (value != null) {
                            elements[3] = value.toString();
                        } else {
                            foundUsage(elements[3]);
                        }
                        node.nodeTAC.set(i, String.format(Locale.ROOT, "\tlist_add %s %s %s", elements[1], elements[2],
                                elements[3]));
                    } else {
                        node.nodeTAC.set(i,
                                String.format(Locale.ROOT, "\tlist_remove %s %s", elements[1], elements[2]));
                    }
                } else if (CONVERSION_OP.contains(elements[1])) {
                    Number right = getConstant(elements[2], constants);
                    if (right != null) {
                        node.nodeTAC.set(i, String.format(Locale.ROOT, "\t%s = %s", elements[0],
                                (elements[1] == "to_float") ? right.doubleValue() : right.intValue()));
                        constants.put(elements[0], (elements[1] == "to_float") ? right.doubleValue() : right.intValue());
                    } else {
                        foundUsage(elements[2]);
                    }
                } else if (elements[0].startsWith("if")) {
                    Number condition = getConstant(elements[1], constants);
                    if (condition != null) {
                        node.nodeTAC.set(i, String.format(Locale.ROOT, "\t%s %s goto %s", elements[0],
                                (condition.intValue() == 1) ? "true" : "false", elements[3]));
                    } else {
                        foundUsage(elements[1]);
                    }
                }

                else if (elements.length == 4) {  // Binary operation
                    Number left = getConstant(elements[1], constants);
                    Number right = getConstant(elements[3], constants);
                    if (left != null && right != null) {
                        Number result = evaluate(left, elements[2], right);
                        constants.put(elements[0], result);
                        node.nodeTAC.set(i, String.format(Locale.ROOT, "\t%s = %s", elements[0], result.toString()));
                    } else {
                        if (left != null) {
                            elements[1] = left.toString();
                        } else {
                            foundUsage(elements[1]);
                        }
                        if (right != null) {
                            elements[3] = right.toString();
                        } else {
                            foundUsage(elements[3]);
                        }
                        node.nodeTAC.set(i, String.format(Locale.ROOT, "\t%s = %s %s %s", elements[0], elements[1],
                                elements[2], elements[3]));
                    }
                } else if (elements.length == 2) {
                    // Case 0 - Pop param (do nothing)
                    if (elements[1].equals("pop_param"))
                        continue;
                    if (elements[0].equals("push_param") || elements[0].equals("return")) {
                        // Case 1 - Push param / Return (it is used to pass parameter)
                        Number param = getConstant(elements[1], constants);
                        if (param != null)
                            node.nodeTAC.set(i, String.format(Locale.ROOT, "\t%s %s", elements[0], param.toString()));
                        else
                            foundUsage(elements[1]);
                    } else {
                        // Case 2 - Assign
                        Number right = getConstant(elements[1], constants);
                        if (right != null) {
                            node.nodeTAC.set(i, String.format(Locale.ROOT, "\t%s = %s", elements[0], right.toString()));
                            constants.put(elements[0], right);
                        } else {
                            foundUsage(elements[1]);
                        }
                    }
                }
            }
        }
    }

    private void eliminateUnusedVariables() {
        for (CFGNode node : CFG) {
            for (int i = 0; i < node.nodeTAC.size(); i++) {
                String[] elements = node.nodeTAC.get(i).trim().split(" = ");
                if (elements.length > 1) {
                    if (!usage.contains(elements[0])) {
                        node.nodeTAC.set(i, null);
                    }
                }
            }
            node.nodeTAC.removeIf(el -> el == null);
        }
    }

    private boolean cutUnreachableBranches() {
        unlinkIfs();
        markNode(CFG.get(0));
        // Remove unmarked nodes
        List<CFGNode> to_remove = CFG.stream().filter(node -> node.visited == false).toList();
        for (CFGNode node : to_remove) {
            for (CFGNode ant : node.antecessors)
                ant.sucessors.remove(node);
            for (CFGNode suc : node.sucessors)
                suc.antecessors.remove(node);
        }
        CFG.removeAll(to_remove);
        return joinNodes();
    }

    // Mark all nodes reachable from the root
    private void markNode(CFGNode node) {
        if (node.visited)
            return;
        node.visited = true;
        for (CFGNode suc : node.sucessors)
            markNode(suc);
    }

    // Join nodes with only one sucessor and one antecessor
    private boolean joinNodes() {
        int i;
        boolean has_joined = true, has_ever_joined = false;
        CFGNode node, sucessor, joined_node;
        while (has_joined) {
            has_joined = false;
            for (i = 0; i < CFG.size(); i++) {
                node = CFG.get(i);
                if (node.sucessors.size() == 1) {
                    sucessor = node.sucessors.iterator().next();
                    if (sucessor.id == node.id)
                        continue;
                    if (sucessor.antecessors.size() != 1 || (!sucessor.antecessors.contains(node)))
                        continue;
                    CFG.remove(node);
                    CFG.remove(sucessor);
                    if (node.id > sucessor.id) {
                        CFGNode temp = node;
                        node = sucessor;
                        sucessor = temp;
                    }
                    String lastInstruction = node.nodeTAC.get(node.nodeTAC.size() - 1);
                    if (lastInstruction.contains("if") || lastInstruction.contains("goto"))
                        node.nodeTAC.remove(node.nodeTAC.size() - 1);
                    if (!sucessor.nodeTAC.get(0).startsWith("\t"))
                        sucessor.nodeTAC.remove(0);
                    joined_node = new CFGNode(node.id);
                    joined_node.nodeTAC.addAll(node.nodeTAC);
                    joined_node.nodeTAC.addAll(sucessor.nodeTAC);
                    joined_node.antecessors = node.antecessors;
                    joined_node.sucessors = sucessor.sucessors;
                    for (CFGNode ant : node.antecessors) {
                        ant.sucessors.remove(node);
                        ant.sucessors.add(joined_node);
                    }
                    for (CFGNode suc : sucessor.sucessors) {
                        suc.antecessors.remove(sucessor);
                        suc.antecessors.add(joined_node);
                    }
                    CFG.add(joined_node);
                    has_joined = true;
                    has_ever_joined = true;
                    break;
                }
            }
        }
        return has_ever_joined;
    }

    // Unlink nodes that are not reachable from the root
    private void unlinkIfs() {
        for (CFGNode node : CFG) {
            node.visited = false;
            for (int i = 0; i < node.nodeTAC.size(); i++) {
                String[] elements = node.nodeTAC.get(i).trim().split(" ");
                if (elements[0].startsWith("if") && BOOLEANS.contains(elements[1])) {
                    boolean goto_label = elements[0].equals("if");
                    boolean next_is_label;
                    if (elements[1].equals("false"))
                        goto_label = !goto_label;
                    Set<CFGNode> newSucessors = new HashSet<>();
                    for (CFGNode sucessor : node.sucessors) {
                        next_is_label = sucessor.nodeTAC.get(0).contains(elements[3]);
                        if (!(next_is_label ^ goto_label)) {
                            newSucessors.add(sucessor);
                        } else {
                            sucessor.antecessors.remove(node);
                        }
                    }
                    node.sucessors = newSucessors;
                }
            }
        }
    }

    // Evaluate binary operation
    private Number evaluate(Number left, String op, Number right) {
        if (left.getClass() == Integer.class) {
            return evaluateInt(left.intValue(), op, right.intValue());
        }
        return evaluateDouble(left.doubleValue(), op, right.doubleValue());
    }

    //
    private Integer evaluateInt(int left, String op, int right) {
        return switch (op) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "**" -> (int) Math.pow(left, right);
            case "//" -> left / right;
            case "%" -> left % right;
            case "==" -> (left == right) ? 1 : 0;
            case "!=" -> (left != right) ? 1 : 0;
            case ">" -> (left > right) ? 1 : 0;
            case "<" -> (left < right) ? 1 : 0;
            case ">=" -> (left >= right) ? 1 : 0;
            case "<=" -> (left <= right) ? 1 : 0;
            default -> null;
        };
    }

    private Double evaluateDouble(double left, String op, double right) {
        return switch (op) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> left / right;
            case "**" -> Math.pow(left, right);
            case "==" -> (left == right) ? 1.0 : 0.0;
            case "!=" -> (left != right) ? 1.0 : 0.0;
            case ">" -> (left > right) ? 1.0 : 0.0;
            case "<" -> (left < right) ? 1.0 : 0.0;
            case ">=" -> (left >= right) ? 1.0 : 0.0;
            case "<=" -> (left <= right) ? 1.0 : 0.0;
            default -> null;
        };
    }


    private Number getConstant(String element, Map<String, Number> constants) {
        try {
            return Integer.valueOf(element);
        } catch (NumberFormatException e) {
        }
        try {
            return Double.valueOf(element);
        } catch (NumberFormatException e) {
        }
        return constants.getOrDefault(element, null);
    }

    // Método para visitar um nó do grafo de fluxo de controlo
    private void visitNode(CFGNode node, List<String> tac, int start_pos) {
        if (node.visited)
            return;
        node.visited = true;
        for (int i = start_pos; i < tac.size(); i++) {
            String instruction = tac.get(i);
            // If is a label, we are starting another node
            if ((!instruction.startsWith("\t")) && (i > start_pos)) {
                visitNode(nextNode(node, i), tac, i);
                return;
            }
            node.nodeTAC.add(instruction);
            // Branch
            Matcher has_goto = goto_pattern.matcher(instruction);
            if (has_goto.find()) {
                String label = has_goto.group(1);
                int next_pos = label_locations.get(label);
                visitNode(nextNode(node, next_pos), tac, next_pos);
                if (instruction.contains("if")) {
                    visitNode(nextNode(node, i + 1), tac, i + 1);
                }
                return;
            }
        }
    }
    // Método para criar um novo nó no grafo de fluxo de controlo
    private CFGNode nextNode(CFGNode node, int pos) {
        var found_node = CFG.stream()
                .filter(el -> el.id == pos)
                .findFirst();
        CFGNode new_node;
        if (found_node.isEmpty()) {
            new_node = new CFGNode(pos);
            CFG.add(new_node);
        } else {
            new_node = found_node.get();
        }
        node.sucessors.add(new_node);
        new_node.antecessors.add(node);
        return new_node;
    }

    // Classe para representar um nó do grafo de fluxo de controlo
    class CFGNode implements Comparable<CFGNode> {
        public Set<CFGNode> antecessors = new HashSet<>();
        public Set<CFGNode> sucessors = new HashSet<>();
        public List<String> nodeTAC = new ArrayList<>();
        int id;
        boolean visited;

        public CFGNode(int id) {
            this.id = id;
            visited = false;
        }

        @Override
        public int compareTo(CFGNode other) {
            return Integer.compare(id, other.id);
        }
    }

    // Método para otimizar o código intermédio (novo método importado)
    public List<String> optimize(List<String> tac) {
        List<String> optimizedTac = new ArrayList<>();

        for (String line : tac) {
            if (line.contains("print")) {
                optimizedTac.add(line.replace("\"! = \"", "\" = \""));  // Exemplo de otimização menor
            } else {
                optimizedTac.add(line);
            }
        }

        return optimizedTac;
    }
}
