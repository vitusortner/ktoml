/**
 * File contains all classes used in Toml AST node
 */

package com.akuleshov7.ktoml.tree

import com.akuleshov7.ktoml.TomlConfig
import com.akuleshov7.ktoml.exceptions.InternalAstException
import com.akuleshov7.ktoml.exceptions.ParseException
import com.akuleshov7.ktoml.parsers.splitKeyToTokens
import com.akuleshov7.ktoml.parsers.trimBrackets
import com.akuleshov7.ktoml.parsers.trimQuotes

/**
 * Base Node class for AST.
 * Toml specification includes a list of supported data types:
 * String, Integer, Float, Boolean, Datetime, Array, and Table.
 *
 * @property content - original node content (used for logging and tests only)
 * @property lineNo - the number of a line from TOML that is linked to the current node
 * @property config
 */
public sealed class TomlNode(
    public open val content: String,
    public open val lineNo: Int,
    public open val config: TomlConfig = TomlConfig()
) {
    public open val children: MutableSet<TomlNode> = mutableSetOf()
    public open var parent: TomlNode? = null

    // the real toml name of a structure (for table [a] it will be "a", for key b = 1 it will be "b")
    // used for logging and errors AND for matching the name of the node to the name of the properties in the class
    // see: [checkMissingRequiredProperties]
    public abstract val name: String

    // this constructor is used by TomlKeyValueList and TomlKeyValuePrimitive and we concatenate keyValuePair to the content
    // only for logging, debug information and unification of the code
    private constructor(
        key: TomlKey,
        value: TomlValue,
        lineNo: Int,
        config: TomlConfig = TomlConfig()) : this(
        "${key.content}=${value.content}",
        lineNo,
        config
    )

    /**
     * @return true if has no children
     */
    public fun hasNoChildren(): Boolean = children.size == 0

    /**
     * @return first child or null
     */
    public fun getFirstChild(): TomlNode? = children.elementAtOrNull(0)

    /**
     * @return all neighbours (all children of current node's parent)
     */
    public open fun getNeighbourNodes(): MutableSet<TomlNode> = parent!!.children

    /**
     * This method performs tree traversal and returns all table Nodes that have proper name and are on the proper level
     *
     * @param searchedTableName - name of the table without braces and trimmed
     * @param searchedLevel - level inside of the tree where this table is stored,
     *                        count of levels in a normal tree that has a TomlFile as a root usually starts from 0
     * @param currentLevel
     * @return a list of table nodes with the same name and that stay on the same level
     */
    private fun findTableInAstByName(
        searchedTableName: String,
        searchedLevel: Int,
        currentLevel: Int
    ): List<TomlTable> {
        val result =
                if (this is TomlTable && this.fullTableName == searchedTableName && currentLevel == searchedLevel) {
                    mutableListOf(this)
                } else {
                    mutableListOf()
                }
        return result + this.children.flatMap {
            if (currentLevel + 1 <= searchedLevel) {
                it.findTableInAstByName(searchedTableName, searchedLevel, currentLevel + 1)
            } else {
                mutableListOf()
            }
        }
    }

    /**
     * This method recursively finds child toml table with the proper name in AST.
     * Stops processing if AST is broken and it has more than one table with the searched name on the same level.
     *
     * @param searchedTableName - the table name that is expected to be found in the list of children of this node
     * @param searchedLevel - the level of nested child node that is searched level (indexed from 1)
     *
     * For example: findTableInAstByName("a.d", 2) will find [a.d] table in the following tree:
     *     a
     *    /  \
     *   a.c a.d
     *        \
     *        a.d.e
     * @return table that was found or null in case of not found
     * @throws ParseException if found several tables with the same name
     */
    public fun findTableInAstByName(searchedTableName: String, searchedLevel: Int): TomlTable? {
        val searchedTable = findTableInAstByName(searchedTableName, searchedLevel, 0)

        if (searchedTable.size > 1) {
            throw ParseException(
                "Internal error: Found several Tables with the same name <$searchedTableName> in AST",
                searchedTable.first().lineNo
            )
        }
        return if (searchedTable.isEmpty()) null else searchedTable[0]
    }

    /**
     * Method inserts a table (section) to tree. It parses the section name and creates all missing nodes in the tree
     * (even parental). For [a.b.c] it will create 3 nodes: a, b, and c
     *
     * @param tomlTable - a table (section) that should be inserted into the tree
     * @return inserted table
     */
    public fun insertTableToTree(tomlTable: TomlTable): TomlNode {
        // prevParentNode - saved node that is used in a chain
        var prevParentNode: TomlNode = this
        // [a.b.c.d] -> for each section node checking existing node in a tree
        // [a], [a.b], [a.b.c], [a.b.c.d] -> if any of them does not exist we create and insert that in a tree
        // 
        // the only trick here is to save the link to the initial tomlTable (append it in the end)
        tomlTable.tablesList.forEachIndexed { level, tableName ->
            val foundTableName = this.findTableInAstByName(tableName, level + 1)

            foundTableName?.let {
                prevParentNode = it
            } ?: run {
                // if we came to the last part of table - just insert our table to the end
                prevParentNode = if (level == tomlTable.tablesList.size - 1) {
                    prevParentNode.appendChild(tomlTable)
                    tomlTable
                } else {
                    // hack and trick to save the link to the initial node (that was passed as an argument) in the tree
                    // so the node will be added only in the end, and it will be the initial node
                    // (!) we will mark these tables with 'isSynthetic' flag
                    val newChildTableName = TomlTable("[$tableName]", lineNo, config, true)
                    prevParentNode.appendChild(newChildTableName)
                    newChildTableName
                }
            }
        }
        return prevParentNode
    }

    /**
     * @param child that will be added to this parent
     */
    public fun appendChild(child: TomlNode) {
        children.add(child)
        child.parent = this
    }

    public fun prettyPrint() {
        prettyPrint(this)
    }

    /**
     * This method returns all available table names that can be found in this particular TOML file
     * (!) it will also return synthetic table nodes, that we generated to create a normal tree structure
     *
     * @return all detected toml tables
     */
    public fun getAllChildTomlTables(): List<TomlTable> {
        val result = if (this is TomlTable) mutableListOf(this) else mutableListOf()
        return result + this.children.flatMap {
            it.getAllChildTomlTables()
        }
    }

    /**
     * find only real table nodes without synthetics
     *
     * @return all real table nodes
     */
    public fun getRealTomlTables(): List<TomlTable> =
            this.getAllChildTomlTables().filter { !it.isSynthetic }

    public companion object {
        // number of spaces that is used to indent levels
        internal const val INDENTING_LEVEL = 4

        /**
         * recursive print the tree using the current node
         *
         * @param node
         * @param level
         */
        public fun prettyPrint(node: TomlNode, level: Int = 0) {
            val spaces = " ".repeat(INDENTING_LEVEL * level)
            println("$spaces - ${node::class.simpleName} (${node.content})")
            node.children.forEach { child ->
                prettyPrint(child, level + 1)
            }
        }
    }
}

/**
 * A root node for TOML Abstract Syntax Tree
 */
public class TomlFile(config: TomlConfig = TomlConfig()) : TomlNode(
    "rootNode",
    0,
    config) {
    override val name: String = "rootNode"

    override fun getNeighbourNodes(): MutableSet<TomlNode> =
            throw InternalAstException("Invalid call to getNeighbourNodes() for TomlFile node")
}

/**
 * tablesList - a list of names of sections (tables) that are included into this particular TomlTable
 * @property isSynthetic - flag to determine that this node was synthetically and there are no such table in the input
 * for example: if the TomlTable is [a.b.c] this list will contain [a], [a.b], [a.b.c]
 */
// FixMe: as diktat fixer can in some cases break the code (https://github.com/cqfn/diKTat/issues/966),
// we will suppress this rule
@Suppress("MULTIPLE_INIT_BLOCKS")
public class TomlTable(
    content: String,
    lineNo: Int,
    config: TomlConfig = TomlConfig(),
    public val isSynthetic: Boolean = false) : TomlNode(
    content,
    lineNo,
    config) {
    // list of tables that are included in this table  (e.g.: {a, a.b, a.b.c} in a.b.c)
    public var tablesList: List<String>

    // short table name (only the name without parental prefix, like a - it is used in decoder and encoder)
    override val name: String

    // this name is used during the injection of the table to the AST
    public val nameWithQuotes: String

    // full name of the table (like a.b.c.d)
    public var fullTableName: String

    // number of nodes in current table (starting from 0)
    internal var level: Int

    init {
        // getting the content inside brackets ([a.b] -> a.b)
        val sectionFromContent = content.trim().trimBrackets().trim()

        if (sectionFromContent.isBlank()) {
            throw ParseException("Incorrect blank table name: $content", lineNo)
        }

        fullTableName = sectionFromContent
        level = sectionFromContent.count { it == '.' }

        val sectionsList = sectionFromContent.splitKeyToTokens(lineNo)
        name = sectionsList.last().trimQuotes()
        nameWithQuotes = sectionsList.last()
        tablesList = sectionsList.mapIndexed { index, _ ->
            (0..index).joinToString(".") { sectionsList[it] }
        }
    }
}

/**
 * Class for parsing and storing Array in AST. It receives a pair of two strings as an input and converts it to a pair
 * of TomlKey and TomlValue (as TomlArray)
 * @property lineNo
 * @property key
 * @property value
 * @property name
 */
public class TomlKeyValueArray(
    override var key: TomlKey,
    override val value: TomlValue,
    override val lineNo: Int,
    override val name: String,
    config: TomlConfig = TomlConfig()
) : TomlNode(
    key,
    value,
    lineNo,
    config), TomlKeyValue {
    // adaptor for string pair of key-value
    public constructor(
        keyValuePair: Pair<String, String>,
        lineNo: Int,
        config: TomlConfig = TomlConfig()
    ) : this(
        TomlKey(keyValuePair.first, lineNo),
        keyValuePair.second.parseList(lineNo, config),
        lineNo,
        TomlKey(keyValuePair.first, lineNo).content
    )
}

/**
 * class for parsing and storing simple single value types in AST
 * @property lineNo
 * @property key
 * @property value
 * @property name
 */
public class TomlKeyValuePrimitive(
    override var key: TomlKey,
    override val value: TomlValue,
    override val lineNo: Int,
    override val name: String,
    config: TomlConfig = TomlConfig()
) : TomlNode(
    key,
    value,
    lineNo,
    config), TomlKeyValue {
    // adaptor for string pair of key-value
    public constructor(
        keyValuePair: Pair<String, String>,
        lineNo: Int,
        config: TomlConfig = TomlConfig()
    ) : this(
        TomlKey(keyValuePair.first, lineNo),
        keyValuePair.second.parseValue(lineNo, config),
        lineNo,
        TomlKey(keyValuePair.first, lineNo).content
    )
}

/**
 * this is a hack to cover empty TOML tables that have missing key-values
 * According the spec: "Empty tables are allowed and simply have no key/value pairs within them."
 *
 * Instances of this stub will be added as children to such parsed tables
 */
public class TomlStubEmptyNode(lineNo: Int, config: TomlConfig = TomlConfig()) : TomlNode(
    "empty_technical_node",
    lineNo,
    config) {
    override val name: String = "empty_technical_node"
}
