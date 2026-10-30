create table cart (
  id            uuid primary key,
  status        varchar(16)   not null check (status in ('OPEN','APPROVED')),
  total_amount  numeric(10,2) not null,
  currency      varchar(3)    not null,
  created_at    timestamptz   not null,
  paid_at       timestamptz,
  version       bigint        not null,
  constraint ck_cart_paid_at check ((status = 'APPROVED') = (paid_at is not null))
);

create table cart_item (
  cart_id     uuid          not null references cart(id),
  line_no     integer       not null,
  slug        varchar(50)   not null,
  name        varchar(120)  not null,
  description varchar(255)  not null,
  unit_price  numeric(10,2) not null,
  currency    varchar(3)    not null,
  primary key (cart_id, line_no),
  unique (cart_id, slug)
);

create table cart_payment_method (
  cart_id        uuid        not null references cart(id),
  code           varchar(32) not null,
  name           varchar(64) not null,
  requires_card  boolean     not null,
  primary key (cart_id, code)
);

create table payment_attempt (
  id           bigserial primary key,
  cart_id      uuid          not null references cart(id),
  method_code  varchar(32)   not null,
  status       varchar(16)   not null check (status in ('APPROVED','DECLINED')),
  amount       numeric(10,2) not null,
  currency     varchar(3)    not null,
  last_digits  varchar(4),
  pix_code     varchar(64),
  created_at   timestamptz   not null,
  constraint ck_attempt_last_digits check (last_digits is null or last_digits ~ '^[0-9]{4}$')
);

create unique index ux_payment_attempt_one_approved
  on payment_attempt (cart_id) where status = 'APPROVED';
create index ix_payment_attempt_cart on payment_attempt (cart_id);
