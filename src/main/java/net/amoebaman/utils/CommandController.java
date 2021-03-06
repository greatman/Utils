package net.amoebaman.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import net.amoebaman.utils.chat.Chat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;

import com.google.common.collect.Lists;

/**
 * Contains interfaces and methods for allowing other plugins to handle commands via annotated methods, much the
 * same way that Bukkit currently handles events.
 * 
 * @author AmoebaMan
 */
public class CommandController implements CommandExecutor{
	
	private final static Set<AnnotatedPluginCommand> commands = new HashSet<AnnotatedPluginCommand>();
	
	/*
	 * This is necessary to ensure that developers don't try to hijack the controller and do janky shit with it
	 */
	private final static CommandController INSTANCE = new CommandController();
	private CommandController(){}
	
	/**
	 * Registers all {@link @CommandHandler}{@code s} within a class with the CommandController, linking them
	 * to their respective commands as defined by the annotation.
	 * @param handler an instance of the class to register
	 */
	public static void registerCommands(Object handler){
		for(Method method : handler.getClass().getMethods())
			if(method.isAnnotationPresent(CommandHandler.class)){
				Class<?>[] params = method.getParameterTypes();
				if(params.length == 2 && CommandSender.class.isAssignableFrom(params[0]) && String[].class.equals(params[1]))
					new AnnotatedPluginCommand(handler, method);
			}
	}
	
	/**
	 * An annotation interface that may be attached to a method to designate it as a command handler.
	 * When registering a handler with this class, only methods marked with this annotation will be considered for command registration.
	 * CommandHandler methods have very loose signature requirements in order to promote flexibility and adaptability.
	 * <br><br>
	 * CommandHandler methods must have two arguments.  The first <b>must</b> be an instance of {@link CommandSender}, though it can be <i>any</i>
	 * instance of CommandSender - if a CommandSender that can't be cast to the required form sends the command, they will automatically
	 * be given a no-go message and the command will not be sent.
	 * <br><br>
	 * The second argument must be either an array of {@link String} or the varargs equivalent, which is used to pass in the arguments.
	 * Arguments begin with the first traditional argument that is not part of the defined command string.
	 * <br><br>
	 * CommandHandlers <i>may return whatever they like</i> - if the method returns something besides void, it will be passed to
	 * {@link Chat#send(CommandSender, Object...)} to be sent to the player as a message.  See the documentation for {@link Chat#send(CommandSender, Object...)}
	 * for more information on how various objects are interpreted and sent to players.
	 * 
	 * @author AmoebaMan
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	public static @interface CommandHandler {
		
		/**
		 * The primary command this method should execute.  This may include spaces, in order to include specific sub-commands.
		 * When the method is called, the arguments list will start with the first argument that isn't part of the whole command.
		 * @return
		 */
		String cmd();
		
		/**
		 * An array containing all aliases for the primary command.  These are functionally identical to the primary command,
		 * but have lower registration priority, and will be ignored if another method designates the same alias as its primary command.
		 * @return
		 */
		String[] aliases() default {};
		
		/**
		 * An array containing all permissions nodes required to run this command.  All permissions must be satisfied in order for
		 * the command to be able to be run.
		 * @return
		 */
		String[] permissions() default {};
		
		/**
		 * The message to be sent to the command sender if they do not have permission to run the command.
		 * @return
		 */
		String permissionMessage() default "You do not have permission to use that command";
	}
	
	private static class AnnotatedPluginCommand{
		
		public final Object instance;
		public final Method method;
		public final Set<String[]> identifiers;
		public final String[] permissions;
		public final String permissionsMessage;
		
		/**
		 * Constructs an AnnotatedPluginCommand containing all the information necessary to
		 * run the command via the CommandController.  This will also pull all information
		 * specified in the CommandHandler interface and assign it to the actual plugin command
		 * at the root of this AnnotatedPluginCommand.
		 * @param instance
		 * @param method
		 */
		public AnnotatedPluginCommand(Object instance, Method method){
			this.instance = instance;
			this.method = method;
			if(instance == null || method == null)
				throw new IllegalArgumentException("instance and method must not be null");
			if(!Lists.newArrayList(instance.getClass().getMethods()).contains(method))
				throw new IllegalArgumentException("instance and method must be part of the same class");
			
			CommandHandler annot = method.getAnnotation(CommandHandler.class);
			if(annot == null)
				throw new IllegalArgumentException("command method must be annotated with @CommandHandler");
			
			identifiers = new HashSet<String[]>();
			if(annot.cmd().trim().isEmpty())
				throw new IllegalArgumentException("command method must have a valid base command");
			identifiers.add(annot.cmd().split(" "));
			for(String alias : annot.aliases())
				if(!alias.trim().isEmpty())
					identifiers.add(alias.split(" "));
			
			Set<String[]> invalid = new HashSet<String[]>();
			for(String[] id : identifiers){
				PluginCommand cmd = Bukkit.getPluginCommand(id[0]);
				if(cmd == null){
					invalid.add(id);
					Logger.getLogger("minecraft").warning("[CommandController] Unable to register command with root identifier (or alias) " + id[0] + ": no Bukkit command is registered with that name");
				}
				else{
					Bukkit.getPluginCommand(id[0]).setExecutor(INSTANCE);
				}
			}
			identifiers.removeAll(invalid);
			
			permissions = annot.permissions();
			permissionsMessage = annot.permissionMessage();
			
			if(!identifiers.isEmpty())
				commands.add(this);
		}
		
		/**
		 * Gets the class of the type of sender that this command needs to be passed to function.
		 * This is determined when the command is registered, by the first parameter type of the method.
		 * Any class assignable from this class can be used to run the command, so using a
		 * <code>CommandSender</code> as the first parameter would allow <i>any</i> sender to run
		 * the command.
		 * @return the necessary sender class
		 */
		public Class<?> getSenderType(){
			return method.getParameterTypes()[0];
		}
		
	}
	
	/**
	 * This is the method that "officially" processes commands, but in reality it will always delegate responsibility to the handlers and methods assigned to the command or subcommand.
	 * Beyond checking permissions, checking player/console sending, and invoking handlers and methods, this method does not actually act on the commands.
	 */
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args){
		
		AnnotatedPluginCommand theCmd = null;
		String[] theId = new String[]{};
		
		/*
		 * For all registered commands...
		 */
		cmds: for(AnnotatedPluginCommand cmd : commands)
			/*
			 * For every identifier for the command...
			 */
			for(String[] id : cmd.identifiers)
				/*
				 * If the root command matches...
				 */
				if(id[0].equalsIgnoreCase(command.getName())){
					/*
					 * Make sure we even have enough args to make the match
					 */
					if(args.length < id.length - 1)
						continue cmds;
					/*
					 * Make sure we've matched all required subcmds as well
					 */
					for(int i = 1; i < id.length; i++)
						if(!id[i].equals(args[i - 1]))
							continue cmds;
					/*
					 * If this is the longest matching command we've seen yet, fixate it
					 */
					if(id.length >= theId.length){
						theCmd = cmd;
						theId = id;
					}
					
				}
		/*
		 * Make sure the command isn't null, we might have been passed the command
		 * because a subcommand was registered but not super-command was
		 */
		if(theCmd == null)
			return false;
		/*
		 * Verify that the correct sender was used
		 */
		if(!theCmd.getSenderType().isAssignableFrom(sender.getClass())){
			sender.sendMessage(ChatColor.RED + "This command must be sent by a " + theCmd.getSenderType().getSimpleName());
			return true;
		}
		/*
		 * Make sure the sender has permissions
		 */
		for(String node : theCmd.permissions)
			if(!sender.hasPermission(node)){
				sender.sendMessage(ChatColor.translateAlternateColorCodes('&', theCmd.permissionsMessage));
				return true;
			}
		/*
		 * Trim down the args and try to process the command
		 */
		String[] newArgs = new String[args.length - (theId.length - 1)];
		for(int i = 0; i < newArgs.length; i++)
			newArgs[i] = args[i + (theId.length - 1)];
		try {
			if(!theCmd.method.getReturnType().equals(Void.class)){
				Object result = theCmd.method.invoke(theCmd.instance, sender, newArgs);
				if(result != null)
					Chat.send(sender, result);
			}
			else
				theCmd.method.invoke(theCmd.instance, sender, newArgs);
			return true;
		}
		catch (Exception e) {
			sender.sendMessage(ChatColor.RED + "An internal error occurred while attempting to perform this command");
			e.printStackTrace();
		}
		
		return false;
		
	}
	
}