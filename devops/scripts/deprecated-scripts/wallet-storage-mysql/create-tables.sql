
drop TABLE if exists items;

drop TABLE if exists wallets;

CREATE TABLE IF NOT EXISTS wallets (
	    id BIGINT(20) NOT NULL AUTO_INCREMENT,
	    name VARCHAR(1024) NOT NULL,
	    metadata VARCHAR(10240) NOT NULL,
	    PRIMARY KEY (id),
	    UNIQUE KEY wallet_name (name)
	) ENGINE=InnoDB DEFAULT CHARSET=latin1;

CREATE TABLE IF NOT EXISTS items (
	    id BIGINT(20) NOT NULL AUTO_INCREMENT,
	    wallet_id BIGINT(20) NOT NULL,
	    type VARCHAR(128) NOT NULL,
	    name VARCHAR(1024) NOT NULL,
	    value LONGBLOB NOT NULL,
	    tags JSON NOT NULL,
	    PRIMARY KEY (id),
	    UNIQUE KEY ux_items_wallet_id_type_name (wallet_id, type, name),
	    CONSTRAINT fk_items_wallet_id FOREIGN KEY (wallet_id)
		REFERENCES wallets (id)
		ON DELETE CASCADE
		ON UPDATE CASCADE
	) ENGINE=InnoDB DEFAULT CHARSET=latin1;
