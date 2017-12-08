package ca.ubc.cs317.dict.net;

import ca.ubc.cs317.dict.exception.DictConnectionException;
import ca.ubc.cs317.dict.model.Database;
import ca.ubc.cs317.dict.model.Definition;
import ca.ubc.cs317.dict.model.MatchingStrategy;
import ca.ubc.cs317.dict.util.DictStringParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.*;

/**
 * Created by Jonatan on 2017-09-09.
 */
public class DictionaryConnection {

    private static final int DEFAULT_PORT = 2628;

    private Socket socket;
    private BufferedReader input;
    private PrintWriter output;

    private Map<String, Database> databaseMap = new LinkedHashMap<String, Database>();

    /** Establishes a new connection with a DICT server using an explicit host and port number, and handles initial
     * welcome messages.
     *
     * @param host Name of the host where the DICT server is running
     * @param port Port number used by the DICT server
     * @throws DictConnectionException If the host does not exist, the connection can't be established, or the messages
     * don't match their expected value.
     */
    public DictionaryConnection(String host, int port) throws DictConnectionException {
        try {
            socket = new Socket(host, port);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            output = new PrintWriter(socket.getOutputStream(), true);
            Status status = Status.readStatus(input);
            if (status.getStatusCode() != 220) {
                throw new DictConnectionException(status.getDetails());
            }
        } catch (Exception e) {
            throw new DictConnectionException(e);
        }
    }

    /** Establishes a new connection with a DICT server using an explicit host, with the default DICT port number, and
     * handles initial welcome messages.
     *
     * @param host Name of the host where the DICT server is running
     * @throws DictConnectionException If the host does not exist, the connection can't be established, or the messages
     * don't match their expected value.
     */
    public DictionaryConnection(String host) throws DictConnectionException {
        this(host, DEFAULT_PORT);
    }

    /** Sends the final QUIT message and closes the connection with the server. This function ignores any exception that
     * may happen while sending the message, receiving its reply, or closing the connection.
     *
     */
    public synchronized void close() {
        if (socket.isConnected()) {
            try {
                output.println("QUIT");
                Status.readStatus(input);
                socket.close();
            } catch (Exception e) {
                // swallow
            }
        }
    }

    /** Requests and retrieves all definitions for a specific word.
     *
     * @param word The word whose definition is to be retrieved.
     * @param database The database to be used to retrieve the definition. A special database may be specified,
     *                 indicating either that all regular databases should be used (database name '*'), or that only
     *                 definitions in the first database that has a definition for the word should be used
     *                 (database '!').
     * @return A collection of Definition objects containing all definitions returned by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Collection<Definition> getDefinitions(String word, Database database) throws DictConnectionException {
        Collection<Definition> set = new ArrayList<>();
        getDatabaseList(); // Ensure the list of databases has been populated

        String command = "DEFINE " + database.getName() + " ";
        // check if we're defining a multi-word phrase
        if (word.split(" ").length > 1) {
            command += ('"' + word + '"');
        } else {
            command += word;
        }

        if (socket.isConnected()) {
            try {
                output.println(command);
                Status status = Status.readStatus(input);
                if (status.getStatusCode() == 552) { // no definition
                    return set;
                }
                if (status.getStatusCode() != 150) { // unexpected code
                    throw new DictConnectionException(status.getDetails());
                }
                set = extractDefinitions(Integer.parseInt(status.getDetails().split(" ")[0]));
            } catch (Exception e) {
                throw new DictConnectionException(e);
            }
        } else {
            throw new DictConnectionException("Disconnected");
        }
        return set;
    }

    /** Extracts definitions from the input.
     * @param numDefs the number of definitions to parse
     * @return Collection<Definition> the list of definitions
     * @throws DictConnectionException If invalid status is parsed
     */
    private synchronized Collection<Definition> extractDefinitions(int numDefs) throws DictConnectionException {
        Collection<Definition> defs = new ArrayList<>();
        for (int i = 0; i < numDefs; i++) {
            Definition def = extractSingleDefinition();
            if (def != null) defs.add(def);
        }
        handleInputEnd();
        return defs;
    }

    private synchronized Definition extractSingleDefinition() throws DictConnectionException {
        Status status = Status.readStatus(input);
        if ((status.getStatusCode() != 151) && (status.getStatusCode() != 131)) {
            return null;
        }
        String[] defDetails = DictStringParser.splitAtoms(status.getDetails());
        String definition = "";
        String word = defDetails[0];
        String db = defDetails[1];

        try {
            while (true) { // grab all the lines of the definition
                String line = input.readLine();
                if (line.equals(".")) {
                    break;
                }
                definition = definition + line + '\n';
            }
        } catch (IOException e) {
            // swallow
        }

        Definition def = new Definition(word, databaseMap.get(db));
        def.setDefinition(definition);
        return def;
    }

    /** Requests and retrieves a list of matches for a specific word pattern.
     *
     * @param word     The word whose definition is to be retrieved.
     * @param strategy The strategy to be used to retrieve the list of matches (e.g., prefix, exact).
     * @param database The database to be used to retrieve the definition. A special database may be specified,
     *                 indicating either that all regular databases should be used (database name '*'), or that only
     *                 matches in the first database that has a match for the word should be used (database '!').
     * @return A set of word matches returned by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Set<String> getMatchList(String word, MatchingStrategy strategy, Database database) throws DictConnectionException {
        Set<String> set = new LinkedHashSet<>();
        String command = "MATCH " + database.getName() + " " + strategy.getName() + " ";
        // check if we're matching a multi-word phrase
        if (word.split(" ").length > 1) {
            command += ('"' + word + '"');
        } else {
            command += word;
        }

        if (socket.isConnected()) {
            try {
                output.println(command);
                Status status = Status.readStatus(input);
                if (status.getStatusCode() == 552) { // no matches
                    return set;
                }
                if (status.getStatusCode() != 152) { // unexpected code
                    throw new DictConnectionException(status.getDetails());
                }
                set = fetchMatches(Integer.parseInt(status.getDetails().split(" ")[0]));
            } catch (Exception e) {
                throw new DictConnectionException(e);
            }
        } else {
            throw new DictConnectionException("Disconnected");
        }

        return set;
    }

    /** Extracts matches from the input.
     * @param numMatches the number of matches to parse.
     * @return Set<String> the list of matches.
     */
    private synchronized Set<String> fetchMatches(int numMatches) {
        Set<String> matches = new LinkedHashSet<>();
        try {
            for (int i = 0; i < numMatches; i++) {
                String[] matchInfo = DictStringParser.splitAtoms(input.readLine());
                matches.add(matchInfo[1]);
            }
            handleInputEnd();
        } catch (IOException e) {
            // swallow
        }

        return matches;

    }

    /** Requests and retrieves a list of all valid databases used in the server. In addition to returning the list, this
     * method also updates the local databaseMap field, which contains a mapping from database name to Database object,
     * to be used by other methods (e.g., getDefinitionMap) to return a Database object based on the name.
     *
     * @return A collection of Database objects supported by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Collection<Database> getDatabaseList() throws DictConnectionException {

        if (!databaseMap.isEmpty()) return databaseMap.values();

        if (socket.isConnected()){
            output.println("SHOW DB");
            try {
                Status status = Status.readStatus(input);
                if (status.getStatusCode() == 554) {
                    return databaseMap.values();
                }
                if (status.getStatusCode() != 110) { // unexpected code
                    throw new DictConnectionException(status.getDetails());
                }

                extractDatabaseDetails(Integer.parseInt(status.getDetails().split(" ")[0]));

            } catch (Exception e){
                throw new DictConnectionException(e);
            }
        } else {
            throw new DictConnectionException("Disconnected");
        }
        return databaseMap.values();
    }

    /** Extracts the database details from the input and adds them to databaseMap.
     * @param numDBs the number of database details to parse
     */
    private synchronized void extractDatabaseDetails(int numDBs) {
        try {
            for (int i = 0; i < numDBs; i++) {
                String[] dbDetail = DictStringParser.splitAtoms(input.readLine());
                databaseMap.put(dbDetail[0], new Database(dbDetail[0], dbDetail[1]));
            }
            handleInputEnd();
        } catch (IOException e) {
            // swallow
        }
    }

    /** Requests and retrieves a list of all valid matching strategies supported by the server.
     *
     * @return A set of MatchingStrategy objects supported by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Set<MatchingStrategy> getStrategyList() throws DictConnectionException {
        Set<MatchingStrategy> strategies = new LinkedHashSet<>();

        if (socket.isConnected()) {
            output.println("SHOW STRAT");
            try {
                Status status = Status.readStatus(input);
                if (status.getStatusCode() == 555) {
                    return strategies;
                }
                if (status.getStatusCode() != 111) { // unexpected code
                    throw new DictConnectionException(status.getDetails());
                }

                strategies = extractStrategies(Integer.parseInt(status.getDetails().split(" ")[0]));

            } catch (Exception e){
                throw new DictConnectionException(e);
            }
        } else {
            throw new DictConnectionException("Disconnected");
        }

        return strategies;
    }



    /** Extracts the list of strategies from the input.
     * @param numStrats the number of strategies to parse
     * @return Set of MatchingStrategy objects.
     * @throws DictConnectionException If the end message code is unexpected.
     */
    private synchronized Set<MatchingStrategy> extractStrategies(int numStrats) throws DictConnectionException {
        Set<MatchingStrategy> strategies = new LinkedHashSet<>();
        try {
            for (int i = 0; i < numStrats; i++) {
                String[] stratDetail = DictStringParser.splitAtoms(input.readLine());
                strategies.add(new MatchingStrategy(stratDetail[0], stratDetail[1]));
            }
            handleInputEnd();
        } catch (IOException e) {
            // swallow
        }
        return strategies;
    }

    /** Flushes the rest of the input (line breaks and ending status codes)
     */
    private synchronized void handleInputEnd() {
        try {
            while(input.ready()) {
                input.readLine();
            }
        } catch (IOException e) {
            // swallow
        }
    }

}
