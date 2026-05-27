package dev.timefiles.miasmartgiftroll.command;

import dev.timefiles.miasmartgiftroll.MiaSmartGiftRoll;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public class SGTabCompleter
implements TabCompleter {
    private final MiaSmartGiftRoll plugin;
    private static final List<String> SUB_COMMANDS = Arrays.asList("gui", "save", "roll", "claim", "reload", "help");
    private static final List<String> FILTER_TYPES = Arrays.asList("all", "perm", "money", "points", "time", "custom");

    public SGTabCompleter(MiaSmartGiftRoll plugin) {
        this.plugin = plugin;
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        ArrayList<String> completions = new ArrayList<String>();
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            completions.addAll(SUB_COMMANDS.stream().filter(cmd -> cmd.startsWith(partial)).collect(Collectors.toList()));
        } else if (args.length >= 2) {
            String subCommand;
            switch (subCommand = args[0].toLowerCase()) {
                case "save": {
                    if (args.length != 2) break;
                    completions.add("<kit_id>");
                    break;
                }
                case "roll": {
                    completions.addAll(this.handleRollCompletion(args));
                }
            }
        }
        return completions;
    }

    private List<String> handleRollCompletion(String[] args) {
        ArrayList<String> completions = new ArrayList<String>();
        switch (args.length) {
            case 2: {
                String partial = args[1].toLowerCase();
                completions.addAll(this.plugin.getKitManager().getKitIds().stream().filter(id -> id.startsWith(partial)).collect(Collectors.toList()));
                break;
            }
            case 3: {
                completions.addAll(Arrays.asList("1", "2", "3", "5", "10"));
                break;
            }
            case 4: {
                String partial = args[3].toLowerCase();
                completions.addAll(FILTER_TYPES.stream().filter(type -> type.startsWith(partial)).collect(Collectors.toList()));
                break;
            }
            case 5: {
                String filterType;
                switch (filterType = args[3].toLowerCase()) {
                    case "perm": {
                        completions.add("<permission.node>");
                        break;
                    }
                    case "money": {
                        completions.addAll(Arrays.asList("100", "1000", "10000"));
                        break;
                    }
                    case "points": {
                        completions.addAll(Arrays.asList("100", "500", "1000"));
                        break;
                    }
                    case "time": {
                        completions.addAll(Arrays.asList("3600", "7200", "36000"));
                        break;
                    }
                    case "custom": {
                        completions.add("%placeholder%");
                    }
                }
                break;
            }
            case 6: {
                if (!args[3].equalsIgnoreCase("custom")) break;
                completions.addAll(Arrays.asList(">=", "<=", ">", "<", "==", "!="));
                break;
            }
            case 7: {
                if (!args[3].equalsIgnoreCase("custom")) break;
                completions.add("<value>");
            }
        }
        return completions;
    }
}



