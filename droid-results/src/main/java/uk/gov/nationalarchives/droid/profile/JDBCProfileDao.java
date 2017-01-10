/**
 * Copyright (c) 2016, The National Archives <pronom@nationalarchives.gsi.gov.uk>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following
 * conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 *  * Neither the name of the The National Archives nor the
 *    names of its contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package uk.gov.nationalarchives.droid.profile;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationMethod;
import uk.gov.nationalarchives.droid.core.interfaces.NodeStatus;
import uk.gov.nationalarchives.droid.core.interfaces.ResourceType;
import uk.gov.nationalarchives.droid.core.interfaces.filter.Filter;
import uk.gov.nationalarchives.droid.core.interfaces.filter.expressions.QueryBuilder;
import uk.gov.nationalarchives.droid.profile.referencedata.Format;
import uk.gov.nationalarchives.droid.results.handlers.ResultHandlerDao;
import uk.gov.nationalarchives.droid.results.handlers.JDBCBatchResultHandlerDao;

import static uk.gov.nationalarchives.droid.profile.SqlUtils.*;

/**
 * Created by Matt Palmer on 19/06/15.
 */
public class JDBCProfileDao implements ProfileDao {
    //CHECKSTYLE:OFF  Sql Statements can break the rules, e.g. commas quite legitimate...
    private static final String INSERT_FORMAT = "INSERT INTO FORMAT (PUID,MIME_TYPE,NAME,VERSION) VALUES (?,?,?,?)";
    private static final String SELECT_MAIN = "SELECT NODE_ID, EXTENSION_MISMATCH, FINISHED_TIMESTAMP, IDENTIFICATION_COUNT, EXTENSION, HASH, "
                                                   + "IDENTIFICATION_METHOD, LAST_MODIFIED_DATE, NAME, NODE_STATUS, RESOURCE_TYPE, FILE_SIZE, "
                                                   + "PARENT_ID, PREFIX, PREFIX_PLUS_ONE, TEXT_ENCODING, URI, " +
            "CASE \n" +
            "\t\t  WHEN NODES.RESOURCE_TYPE = 0 THEN \n" +
            "\t\t  \tCASE\n" +
            "\t\t  \t\twhen NOT EXISTS(SELECT NODE2.PARENT_ID FROM PROFILE_RESOURCE_NODE NODE2 WHERE NODE2.PARENT_ID = NODES.NODE_ID) then true\n" +
            "\t\t  \t\telse false\n" +
            "\t\t  \tEND\n" +
            "\t\t  ELSE false\n" +
            "\t\tEND as EMPTY_DIR " +
            "FROM PROFILE_RESOURCE_NODE NODES ";
    private static final String FIND_CHILD_NODES         = SELECT_MAIN + "WHERE PARENT_ID=?";
    private static final String FIND_TOP_LEVEL_CHILDREN  = SELECT_MAIN +  "WHERE PARENT_ID IS NULL";
    private static final String FIND_CHILDREN            = "SELECT ID.NODE_ID, ID.PUID FROM IDENTIFICATION AS ID "
                                                           + "INNER JOIN PROFILE_RESOURCE_NODE AS PRN "
                                                           + "ON ID.NODE_ID = PRN.NODE_ID";
    private static final String FIND_CHILD_IDS           = FIND_CHILDREN + " AND PRN.PARENT_ID = ?";
    private static final String FIND_TOP_LEVEL_CHILD_IDS = FIND_CHILDREN + " AND PRN.PARENT_ID IS NULL";

    private static final String dummyPuid = "INSERT INTO FORMAT (PUID,MIME_TYPE,NAME,VERSION) VALUES ('','','','')";
    //CHECKSTYLE:ON
    private static final int FORMAT_PUID_INDEX = 1;
    private static final int FORMAT_MIME_TYPE_INDEX = 2;
    private static final int FORMAT_NAME_INDEX = 3;
    private static final int FORMAT_VERSION_INDEX = 4;

    private final Log log = LogFactory.getLog(getClass());

    private DataSource datasource;
    private ResultHandlerDao resultHandlerDao;

    private JdbcTemplate jdbcTemplate;

    /**
     *
     * @param datasource The SQL datasource to use.
     */
    public void setDatasource(DataSource datasource) {
        this.datasource = datasource;
        this.jdbcTemplate = new JdbcTemplate(datasource);
    }

    /**
     *
     * @param resultHandlerDao The resultHandlerDao to assign.
     */
    public void setResultHandlerDao(ResultHandlerDao resultHandlerDao) {
        this.resultHandlerDao = resultHandlerDao;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Format> getAllFormats() {
        return resultHandlerDao.getAllFormats();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void saveFormat(final Format format) {
        try {
            final Connection conn = datasource.getConnection();
            try {
                // BNO getInsertFormatStatement tries to convert the empty Puid to NULL, causing a failed
                // database insert. So as a workaround for now we specify the SQL directly for this case.
                final PreparedStatement insertFormat =
                        (format == Format.NULL)
                                ? conn.prepareStatement(dummyPuid) : getInsertFormatStatement(conn, format);
                insertFormat.execute();
                conn.commit();
            } finally {
                conn.close();
            }
        } catch (SQLException e) {
            log.error("A database exception occurred inserting a format " + format, e);
        }
    }


    private static final RowMapper<ProfileResourceNode> PROFILE_RESOURCE_NODE_ROW_MAPPER = new RowMapper<ProfileResourceNode>() {
        @Override
        public ProfileResourceNode mapRow(ResultSet rs, int rowNum) throws SQLException {

            URI uri;
            String uriString = rs.getString("URI");
            try {
                uri = new URI(uriString);

            } catch (URISyntaxException e) {
                throw new SQLException("The URI for the node obtained from the database: [" + uriString
                        + "] could not be converted into a URI", e);
            }

            NodeMetaData nodeMetaData = new NodeMetaData();
            ProfileResourceNode node = new ProfileResourceNode(uri);
            node.setMetaData(nodeMetaData);

            node.setId(rs.getLong("NODE_ID"));
            node.setExtensionMismatch(rs.getBoolean("EXTENSION_MISMATCH"));
            node.setFinished(getNullableTimestamp("FINISHED_TIMESTAMP", rs));

                //getNullableLong("IDENTIFICATION_COUNT", rs); not used in original

            nodeMetaData.setExtension(getNullableString("EXTENSION",rs));
            nodeMetaData.setHash(getNullableString("HASH",rs));

            Integer identificationMethodIndex = getNullableInteger("IDENTIFICATION_METHOD", rs);
            nodeMetaData.setIdentificationMethod(identificationMethodIndex == null ? null : IdentificationMethod.values()[identificationMethodIndex]);

            nodeMetaData.setLastModifiedDate(getNullableTimestamp("LAST_MODIFIED_DATE",rs));
            nodeMetaData.setName(rs.getString("NAME"));

            Integer nodeStatusIndex = getNullableInteger("NODE_STATUS", rs);
            nodeMetaData.setNodeStatus( nodeStatusIndex == null ? null : NodeStatus.values()[nodeStatusIndex]);

            nodeMetaData.setResourceType(ResourceType.values()[rs.getInt("RESOURCE_TYPE")]);

            nodeMetaData.setSize(getNullableLong("FILE_SIZE", rs));
            node.setParentId(getNullableLong("PARENT_ID",rs));
            node.setPrefix(getNullableString("PREFIX", rs));
            node.setPrefixPlusOne(getNullableString("PREFIX_PLUS_ONE",rs));
                //getNullableInteger("TEXT_ENCODING",rs); we dont using this in original

            node.setFilterStatus(1); //TODO What is this?

            boolean emptyDir = rs.getBoolean("EMPTY_DIR");
            if(emptyDir && nodeMetaData.getNodeStatus() == NodeStatus.DONE) {
                nodeMetaData.setNodeStatus(NodeStatus.EMPTY);
            }


            return node;
        }
    };



    /**
     * {@inheritDoc}
     */
    @Override
    public List<ProfileResourceNode> findProfileResourceNodes(final Long parentId) {

        try {
            final List<ProfileResourceNode> childNodes;
            if (parentId == null) {
                childNodes = jdbcTemplate.query(FIND_TOP_LEVEL_CHILDREN, PROFILE_RESOURCE_NODE_ROW_MAPPER);
            } else {
                childNodes = jdbcTemplate.query(FIND_CHILD_NODES, PROFILE_RESOURCE_NODE_ROW_MAPPER, parentId);
            }


            jdbcTemplate.execute(new ConnectionCallback<Boolean>() {
                @Override
                public Boolean doInConnection(Connection con) throws SQLException, DataAccessException {
                    loadIdentifications(parentId, con, childNodes);
                    return false;
                }
            });

            return childNodes;
        }catch (DataAccessException ex) {
            log.error("A database exception occurred finding nodes with parent id " + parentId, ex);
        }
        return Collections.emptyList();
    }


    /**
     * {@inheritDoc}
     */

    @Override
    public List<ProfileResourceNode> findProfileResourceNodes(final Long parentId,
                                                              final Filter filter) {
        final QueryBuilder queryBuilder = SqlUtils.getQueryBuilder(filter);
        final String ejbFilter = queryBuilder.toEjbQl();
        final String query = getSQLQueryString(ejbFilter, parentId);

        try {
            final Connection conn = datasource.getConnection();
            try {
                final PreparedStatement statement = conn.prepareStatement(query);
                setFilterParameters(statement, queryBuilder, parentId);
                final ResultSet results = statement.executeQuery();
                final List<ProfileResourceNode> filteredNodes = buildNodes(results);
                //TODO: deal with filter status.
                loadIdentifications(parentId, conn, filteredNodes);
                return filteredNodes;
            } finally {
                conn.close();
            }
        } catch (SQLException e) {
            log.error("A database exception occurred finding filtered nodes with parent id " + parentId, e);
        }
        return Collections.emptyList();
    }

    @Override
    public void initialise() {
        populateResultHandlerReferenceData();
    }


    private void loadIdentifications(Long parentId, Connection conn, List<ProfileResourceNode> childNodes)
        throws SQLException {
        if (childNodes.size() > 0) {
            final String childQuery = parentId == null ? FIND_TOP_LEVEL_CHILD_IDS : FIND_CHILD_IDS;
            final PreparedStatement findIdentifications = conn.prepareStatement(childQuery);
            if (parentId != null) {
                SqlUtils.setNullableLong(1, parentId, findIdentifications);
            }
            final ResultSet identifications = findIdentifications.executeQuery();
            addIdentificationsToNodes(identifications, childNodes, resultHandlerDao.getPUIDFormatMap());
        }
    }

    private void setFilterParameters(final PreparedStatement statement,
                                     final QueryBuilder queryBuilder,
                                     final Long parentId) throws SQLException {
        final Object[] parameters = queryBuilder.getValues();
        int paramCount = 1;
        String nullFilterMessage = "A null filter parameter was encountered at position ";

        // Set the filter parameters for the first part of the query.
        for (final Object parameter : parameters) {
            if (parameter != null) {
                SqlUtils.setNonNullableParameter(paramCount++, parameter, statement);
            } else {
                log.warn(nullFilterMessage + (paramCount - 1));
            }
        }
        // Set the same filter parameters for the second part of the query.
        for (final Object parameter : parameters) {
            if (parameter != null) {
                SqlUtils.setNonNullableParameter(paramCount++, parameter, statement);
            } else {
                log.warn(nullFilterMessage + (paramCount - 1));
            }
        }
        // Set the parent id of the children if not null.
        if (parentId != null) {
            statement.setLong(paramCount, parentId);
        }
    }

    private String getSQLQueryString(final String ejbFilter, final Long parentId) {
        boolean formatCriteriaExist = formatCriteriaExist(ejbFilter);
        boolean formatMetadataExist = formatCriteriaExist && formatMetadataExist(ejbFilter);
        String filterCriteriaDirect = SqlUtils.transformEJBtoSQLFields(ejbFilter,
                "profile", "form");
        String filterCriteriaChild = SqlUtils.transformEJBtoSQLFields(ejbFilter,
                "children", "child_form");

        String query = formatCriteriaExist ? "select distinct profile.*," : "select profile.*,";
        query += " case when (" + filterCriteriaDirect + ") then 1"
                + " else case when profile.resource_type <> 2 and exists ("
                + " select children.node_id from profile_resource_node as children";

        if (formatCriteriaExist) { // if we have filter conditions on the file formats:
            if (formatMetadataExist) { // join to the format table through identifications.
                query = query + " inner join identification as child_ident on child_ident.node_id = children.node_id"
                        + " inner join format as child_form on child_form.puid = child_ident.puid";
            } else { // a puid-only format query only needs to join to identifications.
                query = query + " inner join identification as child_form on child_form.node_id = children.node_id";
            }
        }

        query = query + " where children.prefix > profile.prefix and children.prefix < profile.prefix_plus_one"
                + " and (" + filterCriteriaChild + ")) then 2 else 0 end end as FilterStatus"
                + " from profile_resource_node as profile";

        if (formatCriteriaExist) { // if we have filter conditions on the file formats:
            if (formatMetadataExist) { // join to the format table through identifications.
                query = query + " inner join identification as ident on ident.node_id = profile.node_id"
                        + " inner join format as form on form.puid = ident.puid";
            } else { // // a puid-only format query only needs to join to identifications.
                query = query + " inner join identification as form on form.node_id = profile.node_id";
            }
        }

        return query + " where profile.parent_id " + getParentIdQuery(parentId);
    }

    private boolean formatCriteriaExist(final String filter) {
        return filter.contains("format.");
    }

    private boolean formatMetadataExist(final String filter) {
        return filter.contains("format.mimeType") || filter.contains("format.name");
    }

    private PreparedStatement getInsertFormatStatement(Connection conn, Format format) throws SQLException {
        final PreparedStatement insertFormat = conn.prepareStatement(INSERT_FORMAT);
        insertFormat.setString(FORMAT_PUID_INDEX, format.getPuid());
        SqlUtils.setNullableString(FORMAT_MIME_TYPE_INDEX, format.getMimeType(), insertFormat);
        SqlUtils.setNullableString(FORMAT_NAME_INDEX, format.getName(), insertFormat);
        SqlUtils.setNullableString(FORMAT_VERSION_INDEX, format.getVersion(), insertFormat);
        return insertFormat;
    }

    private List<ProfileResourceNode> buildNodes(final ResultSet children) throws SQLException {
        final List<ProfileResourceNode> childNodes = new ArrayList<ProfileResourceNode>(32);
        while (children.next()) {
            final ProfileResourceNode child = SqlUtils.buildProfileResourceNode(children);
            if (child.getFilterStatus() > 0) {
                childNodes.add(child);
            }
        }
        return childNodes;
    }

    private void addIdentificationsToNodes(final ResultSet identifications,
                                           final List<ProfileResourceNode> childNodes,
                                           final Map<String, Format> puidFormatMap) throws SQLException {
        final Map<Long, ProfileResourceNode> nodeIdMap = buildNodeIdMap(childNodes);
        while (identifications.next()) {
            final ProfileResourceNode node   = nodeIdMap.get(identifications.getLong(1));
            final Format              format = puidFormatMap.get(identifications.getString(2));
            if (node != null && format != null) {
                node.addFormatIdentification(format);
            }

            // Identification count will be null by default.  Set it to zero if there are no identificatiosn, so that
            // the GUI displays the appropriate icon.
            for (ProfileResourceNode child : childNodes) {
                if (child.getIdentificationCount() == null
                    && child.getMetaData().getResourceType() != ResourceType.FOLDER) {
                    child.setZeroIdentifications();
                }
            }
        }
    }

    private Map<Long, ProfileResourceNode> buildNodeIdMap(List<ProfileResourceNode> nodes) {
        final Map<Long, ProfileResourceNode> nodeIdMap = new HashMap<Long, ProfileResourceNode>(nodes.size() * 2);
        for (final ProfileResourceNode node : nodes) {
            nodeIdMap.put(node.getId(), node);
        }
        return nodeIdMap;
    }

    private String getParentIdQuery(Long parentId) {
        return parentId == null ? "is null" : " = ?";
    }

    private void populateResultHandlerReferenceData() {
        if (this.resultHandlerDao instanceof JDBCBatchResultHandlerDao) {
            //initalize the formats
            JDBCBatchResultHandlerDao batchResultHandler = (JDBCBatchResultHandlerDao) this.resultHandlerDao;
            batchResultHandler.initialiseForNewTemplate();
        }
    }
}
