/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2008-2013, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.engine.spi;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import org.hibernate.AssertionFailure;
import org.hibernate.engine.internal.EssentialEntityPersisterDetails;
import org.hibernate.internal.util.compare.EqualsHelper;
import org.hibernate.persister.entity.EntityEssentials;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.pretty.MessageHelper;
import org.hibernate.type.Type;

/**
 * Uniquely identifies of an entity instance in a particular Session by identifier.
 * Note that it's only safe to be used within the scope of a Session: it doesn't consider for example the tenantId
 * as part of the equality definition.
 * <p/>
 * Information used to determine uniqueness consists of the entity-name and the identifier value (see {@link #equals}).
 * <p/>
 * Performance considerations: lots of instances of this type are created at runtime. Make sure each one is as small as possible
 * by storing just the essential needed.
 *
 * @author Gavin King
 * @author Sanne Grinovero
 */
public final class EntityKey implements Serializable {

	private final Serializable identifier;
	private final int hashCode;
	private final EntityEssentials persister;

	/**
	 * Construct a unique identifier for an entity class instance.
	 * <p>
	 * NOTE : This signature has changed to accommodate both entity mode and multi-tenancy, both of which relate to
	 * the Session to which this key belongs.  To help minimize the impact of these changes in the future, the
	 * {@link SessionImplementor#generateEntityKey} method was added to hide the session-specific changes.
	 *
	 * @param id The entity id
	 * @param persister The entity persister
	 */
	public EntityKey(Serializable id, EntityPersister persister) {
		this.persister = persister;
		if ( id == null ) {
			throw new AssertionFailure( "null identifier" );
		}
		this.identifier = id;
		this.hashCode = generateHashCode();
	}

	/**
	 * Used to reconstruct an EntityKey during deserialization. Note that this constructor
	 * is used only in very specific situations: the SessionFactory isn't actually available
	 * and so both equals and hashcode implementations can't be implemented correctly.
	 *
	 * @param identifier The identifier value
	 * @param fakePersister Is a placeholder for the EntityPersister, only providing essential methods needed for this purpose.
	 * @param hashCode The hashCode needs to be provided as it can't be calculated correctly without the SessionFactory.
	 */
	private EntityKey(Serializable identifier, EntityEssentials fakePersister, int hashCode) {
		this.persister = fakePersister;
		if ( identifier == null ) {
			throw new AssertionFailure( "null identifier" );
		}
		this.identifier = identifier;
		this.hashCode = hashCode;
	}

	private int generateHashCode() {
		int result = 17;
		result = 37 * result + persister.getIdentifierType().getHashCode( identifier, persister.getFactory() );
		return result;
	}

	public boolean isBatchLoadable() {
		return persister.isBatchLoadable();
	}

	public Serializable getIdentifier() {
		return identifier;
	}

	public String getEntityName() {
		return persister.getEntityName();
	}

	@Override
	public boolean equals(Object other) {
		if ( this == other ) {
			return true;
		}
		if ( other == null || EntityKey.class != other.getClass() ) {
			return false;
		}

		final EntityKey otherKey = (EntityKey) other;
		return samePersistentType( otherKey )
				&& sameIdentifier( otherKey );

	}

	private boolean sameIdentifier(final EntityKey otherKey) {
		return persister.getIdentifierType().isEqual( otherKey.identifier, this.identifier, persister.getFactory() );
	}

	private boolean samePersistentType(final EntityKey otherKey) {
		if ( otherKey.persister == persister ) {
			return true;
		}
		else {
			return EqualsHelper.equals( otherKey.persister.getRootEntityName(), persister.getRootEntityName() );
		}
	}

	@Override
	public int hashCode() {
		return hashCode;
	}

	@Override
	public String toString() {
		return "EntityKey" +
				MessageHelper.infoString( this.persister, identifier, persister.getFactory() );
	}

	/**
	 * Custom serialization routine used during serialization of a
	 * Session/PersistenceContext for increased performance.
	 *
	 * @param oos The stream to which we should write the serial data.
	 *
	 * @throws IOException Thrown by Java I/O
	 */
	public void serialize(ObjectOutputStream oos) throws IOException {
		oos.writeObject( persister.getIdentifierType() );
		oos.writeBoolean( isBatchLoadable() );
		oos.writeObject( identifier );
		oos.writeObject( persister.getEntityName() );
		oos.writeObject( persister.getRootEntityName() );
		oos.writeInt( hashCode );
	}

	/**
	 * Custom deserialization routine used during deserialization of a
	 * Session/PersistenceContext for increased performance.
	 *
	 * @param ois The stream from which to read the entry.
	 * @param sessionFactory The SessionFactory owning the Session being deserialized.
	 *
	 * @return The deserialized EntityEntry
	 *
	 * @throws IOException Thrown by Java I/O
	 * @throws ClassNotFoundException Thrown by Java I/O
	 */
	public static EntityKey deserialize(ObjectInputStream ois, SessionFactoryImplementor sessionFactory) throws IOException, ClassNotFoundException {
		final Type identifierType = (Type) ois.readObject();
		final boolean isBatchLoadable = ois.readBoolean();
		final Serializable id = (Serializable) ois.readObject();
		final String entityName = (String) ois.readObject();
		final String rootEntityName = (String) ois.readObject();
		final int hashCode = ois.readInt();
		if ( sessionFactory != null) {
			final EntityPersister entityPersister = sessionFactory.getEntityPersister( entityName );
			return new EntityKey(id, entityPersister);
		}
		else {
			//This version will produce an EntityKey which is technically unable to satisfy the equals contract!
			final EntityEssentials fakePersister = new EssentialEntityPersisterDetails(identifierType, isBatchLoadable, entityName, rootEntityName);
			return new EntityKey(id, fakePersister, hashCode);
		}
	}
}
